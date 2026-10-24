package org.rpgengine.characters;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure resource rules: inclusive bounds, overflow and rejected changes leave values intact. */
class ActorResourceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    /** Construct a stateless test fixture; no external inputs or failure paths. */
    ActorResourceTest() {
    }

    /** Creation rejects inverted bounds, out-of-range values and invalid revisions/keys. */
    @Test
    void rejectsInvalidCreation() {
        assertThrows(IllegalArgumentException.class, () -> resource(10, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> resource(0, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> resource(0, 11, 10));
        assertThrows(IllegalArgumentException.class,
                () -> ActorResource.create(actor(), "health", 0, 5, 10, -1, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ActorResource.create(actor(), " ", 0, 5, 10, 0, NOW));
    }

    /** Exact endpoints are legal; unaffordable costs, overflow and stale revisions never mutate. */
    @Test
    void adjustsWithinInclusiveBoundsAndRejectsWithoutMutation() {
        ActorResource resource = resource(0, 5, 10);
        resource.adjust(-5, 1, NOW.plusSeconds(1));
        assertEquals(0, resource.getCurrentValue());
        resource.adjust(10, 2, NOW.plusSeconds(2));
        assertEquals(10, resource.getCurrentValue());
        assertThrows(IllegalArgumentException.class, () -> resource.adjust(1, 3, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> resource.adjust(-11, 3, NOW.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> resource.adjust(-1, 1, NOW.plusSeconds(3)));
        assertThrows(NullPointerException.class, () -> resource.adjust(-1, 3, null));
        assertEquals(10, resource.getCurrentValue());
        assertEquals(2, resource.getUpdatedRevision());
        assertEquals(NOW.plusSeconds(2), resource.getUpdatedAt());
        ActorResource large = resource(0, Long.MAX_VALUE, Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> large.adjust(1, 1, NOW));
        assertEquals(Long.MAX_VALUE, large.getCurrentValue());
        ActorResource signed = resource(Long.MIN_VALUE, Long.MIN_VALUE, 0);
        assertThrows(IllegalArgumentException.class, () -> signed.adjust(-1, 1, NOW));
        assertEquals(Long.MIN_VALUE, signed.getCurrentValue());
    }

    /** Bounds can change only if the existing value fits; failure leaves both bounds intact. */
    @Test
    void changesBoundsWithoutClamping() {
        ActorResource resource = resource(0, 5, 10);
        resource.changeBounds(5, 5, 1, NOW.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> resource.changeBounds(6, 10, 2, NOW));
        assertThrows(IllegalArgumentException.class, () -> resource.changeBounds(0, 4, 2, NOW));
        assertThrows(IllegalArgumentException.class, () -> resource.changeBounds(0, 10, 0, NOW));
        assertEquals(5, resource.getMinimumValue());
        assertEquals(5, resource.getMaximumValue());
        assertEquals(1, resource.getUpdatedRevision());
    }

    /** @return an unsaved actor with a fresh campaign; invalid fixture data fails creation */
    private static Actor actor() {
        return Actor.create(UUID.randomUUID(), ActorKind.CHARACTER, null, "Hero", null, 0, NOW);
    }

    /** @return an unsaved health resource; invalid bounds propagate the domain exception */
    private static ActorResource resource(long minimum, long current, long maximum) {
        return ActorResource.create(actor(), "health", minimum, current, maximum, 0, NOW);
    }
}
