package org.rpgengine.campaign;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.memory.CampaignCheckpoint;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.presentation.SupportedLocale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verify campaign creation, counter validation and record validation without a
 * database: explicit adventure ownership, initial canonical counters, event envelopes,
 * checkpoint mapping and the shared value checks.
 */
class CampaignFoundationsTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00.123456789Z");
    private static final String HASH = "a".repeat(64);

    /** A new campaign belongs to the revision's adventure and starts at zero. */
    @Test
    void startsCampaignPinnedToItsAdventureRevision() {
        UUID adventure = UUID.randomUUID();
        ContentRevision revision = revision(adventure);
        Campaign campaign = Campaign.start(adventure, revision, "Moonfall", "seed-1", NOW);
        assertEquals(adventure, campaign.getAdventureId());
        assertEquals(revision.getId(), campaign.getContentRevisionId());
        assertEquals(CampaignMode.EXPLORATION, campaign.getMode());
        assertEquals(CampaignCounters.of(0, 0, 0, 0), campaign.getCounters());
        assertNull(campaign.getPendingDecision());
        // Sub-microsecond precision is dropped so reloads from PostgreSQL compare equal.
        assertEquals(Instant.parse("2026-10-24T10:00:00.123456Z"), campaign.getCreatedAt());
        assertThrows(IllegalArgumentException.class,
                () -> Campaign.start(UUID.randomUUID(), revision, "Moonfall", "seed-1", NOW));
        assertThrows(IllegalArgumentException.class, () -> Campaign.start(adventure, revision, " ", "seed", NOW));
    }

    /** Counters are non-negative and compare by value. */
    @Test
    void validatesCanonicalCounters() {
        assertEquals(CampaignCounters.initial(), CampaignCounters.of(0, 0, 0, 0));
        assertEquals(CampaignCounters.of(1, 2, 3, 4).hashCode(), CampaignCounters.of(1, 2, 3, 4).hashCode());
        assertThrows(IllegalArgumentException.class, () -> CampaignCounters.of(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CampaignCounters.of(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CampaignCounters.of(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> CampaignCounters.of(0, 0, 0, -1));
    }

    /** Counter overflow and stale revisions must leave every world field unchanged, even in memory. */
    @Test
    void rejectsWorldOverflowWithoutPartialMutation() {
        UUID adventure = UUID.randomUUID();
        Campaign campaign = Campaign.start(adventure, revision(adventure), "Campaign", "seed", NOW);
        var before = campaign.commitWorld(0, false, Long.MAX_VALUE, 1,
                CampaignMode.EXPLORATION, "{\"kind\":\"choose\"}", NOW);
        assertThrows(ArithmeticException.class, () -> campaign.commitWorld(1, true, 1, 1,
                CampaignMode.EXPLORATION, null, NOW.plusSeconds(1)));
        assertEquals(before, campaign.getCounters());
        assertEquals("{\"kind\":\"choose\"}", campaign.getPendingDecision());
        assertEquals(campaign.getCreatedAt(), campaign.getUpdatedAt());
        assertThrows(IllegalStateException.class, () -> campaign.commitWorld(0, true, 0, 1,
                CampaignMode.EXPLORATION, null, NOW));
        assertEquals(before, campaign.getCounters());
    }

    /** Event envelopes and bodies reject malformed values before reaching SQL. */
    @Test
    void validatesEventRecords() {
        UUID campaign = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new CampaignEvent.Envelope(campaign, 0, operation, null,
                null, UUID.randomUUID(), 0, 0, HASH, EventVisibility.PUBLIC));
        assertThrows(IllegalArgumentException.class, () -> new CampaignEvent.Envelope(campaign, 1, operation, null,
                null, UUID.randomUUID(), 0, 0, "not-a-hash", EventVisibility.PUBLIC));
        assertThrows(NullPointerException.class, () -> new CampaignEvent.Envelope(campaign, 1, null, null,
                null, UUID.randomUUID(), 0, 0, HASH, EventVisibility.PUBLIC));
        assertThrows(IllegalArgumentException.class, () -> new CampaignEvent.Body("door.opened", 0, 1, "{}"));
        assertThrows(IllegalArgumentException.class, () -> new CampaignEvent.Body("door.opened", 1, 1, "[1]"));
        CampaignEvent event = CampaignEvent.append(new CampaignEvent.Envelope(campaign, 1, operation, null, null,
                UUID.randomUUID(), 0, 0, HASH, EventVisibility.INTERNAL),
                new CampaignEvent.Body("door.opened", 1, 1, "{}"), NOW);
        assertEquals(campaign, event.getCampaignId());
        assertEquals(EventVisibility.INTERNAL, event.getEnvelope().visibility());
    }

    /** A checkpoint keeps the counters it was captured at, with the event sequence as high-water mark. */
    @Test
    void capturesCheckpointCounters() {
        CampaignCounters counters = CampaignCounters.of(7, 3, 600, 12);
        CampaignCheckpoint checkpoint = CampaignCheckpoint.capture(UUID.randomUUID(), counters,
                new CampaignCheckpoint.Hashes(HASH, HASH, HASH), 1, "{}", NOW);
        assertEquals(counters, checkpoint.getCounters());
        assertThrows(IllegalArgumentException.class, () -> CampaignCheckpoint.capture(UUID.randomUUID(), counters,
                new CampaignCheckpoint.Hashes(HASH, HASH, HASH), 0, "{}", NOW));
        assertThrows(IllegalArgumentException.class, () -> new CampaignCheckpoint.Hashes(HASH, HASH, "A".repeat(64)));
    }

    /** Shared value checks enforce the documented bounds and formats. */
    @Test
    void enforcesSharedValueChecks() {
        assertEquals("abc", CanonicalValues.requireText("abc", "f", 3));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requireText("abcd", "f", 3));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requireText("  ", "f", 3));
        assertNull(CanonicalValues.optionalText(null, "f", 3));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requireSha256Hex("a".repeat(63), "h"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requirePositive(0, "n"));
        assertEquals(0, CanonicalValues.requireNonNegative(0, "n"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requireJsonObject("\"text\"", "j", 64));
        // Count UTF-8 bytes: the two accented letters make this object ten bytes long.
        assertEquals("{\"a\":1}", CanonicalValues.requireJsonObject("{\"a\":1}", "j", 7));
        assertThrows(IllegalArgumentException.class, () -> CanonicalValues.requireJsonObject("{\"a\":1} ", "j", 7));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalValues.requireJsonObject("{\"éé\":0}", "j", 9));
    }

    /** @return an unsaved admitted revision owned by the given adventure */
    private static ContentRevision revision(UUID adventure) {
        return ContentRevision.admit(adventure,
                new ContentRevision.PackCoordinates("pack.moonfall", "1.0.0", HASH, 1, SupportedLocale.EN),
                new ContentRevision.RulesetCoordinates("core", "1.0.0", HASH), NOW);
    }
}
