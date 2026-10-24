package org.rpgengine.commands;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify the docs/07 operation lifecycle table and the journal entry rules without a
 * database: allowed and forbidden transitions, commit-only-through-recordCommit, and
 * the operation version as the row's own counter.
 */
class CampaignOperationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String HASH = "0123456789abcdef".repeat(4);

    /** The lifecycle table matches the docs/07 state diagram exactly. */
    @Test
    void lifecycleTableMatchesTheSpecification() {
        assertEquals(EnumSet.of(OperationStatus.PLANNING, OperationStatus.FAILED_BEFORE_COMMIT),
                OperationStatus.ACCEPTED.successors());
        assertEquals(EnumSet.of(OperationStatus.NEEDS_CLARIFICATION, OperationStatus.VALIDATING,
                OperationStatus.CANCELLED, OperationStatus.FAILED_BEFORE_COMMIT), OperationStatus.PLANNING.successors());
        assertEquals(EnumSet.of(OperationStatus.REJECTED, OperationStatus.COMMITTING, OperationStatus.CANCELLED,
                OperationStatus.FAILED_BEFORE_COMMIT), OperationStatus.VALIDATING.successors());
        assertEquals(EnumSet.of(OperationStatus.COMMITTED), OperationStatus.COMMITTING.successors());
        assertEquals(EnumSet.of(OperationStatus.NARRATING), OperationStatus.COMMITTED.successors());
        assertEquals(EnumSet.of(OperationStatus.DELIVERED, OperationStatus.COMMITTED_NARRATION_PENDING),
                OperationStatus.NARRATING.successors());
        // Once committing has started, cancellation is no longer possible.
        assertFalse(OperationStatus.COMMITTING.canTransitionTo(OperationStatus.CANCELLED));
        for (OperationStatus terminal : EnumSet.of(OperationStatus.NEEDS_CLARIFICATION, OperationStatus.REJECTED,
                OperationStatus.DELIVERED, OperationStatus.COMMITTED_NARRATION_PENDING, OperationStatus.CANCELLED,
                OperationStatus.FAILED_BEFORE_COMMIT)) {
            assertTrue(terminal.isTerminal(), terminal.name());
        }
        assertEquals(EnumSet.of(OperationStatus.COMMITTED, OperationStatus.NARRATING, OperationStatus.DELIVERED,
                OperationStatus.COMMITTED_NARRATION_PENDING),
                EnumSet.copyOf(java.util.Arrays.stream(OperationStatus.values())
                        .filter(OperationStatus::isCommitted).toList()));
    }

    /** A full successful path bumps the operation version once per transition. */
    @Test
    void walksTheCommittedPath() {
        CampaignOperation operation = CampaignOperation.accept(UUID.randomUUID(), "k1", HASH, "explore", 3, NOW);
        assertEquals(OperationStatus.ACCEPTED, operation.getStatus());
        assertEquals(0, operation.getOperationVersion());
        assertNull(operation.getCommittedRevision());

        operation.transitionTo(OperationStatus.PLANNING, NOW);
        operation.transitionTo(OperationStatus.VALIDATING, NOW);
        operation.transitionTo(OperationStatus.COMMITTING, NOW);
        operation.recordCommit(4, "{\"ok\":true}", NOW.plusSeconds(1));
        operation.transitionTo(OperationStatus.NARRATING, NOW.plusSeconds(2));
        operation.transitionTo(OperationStatus.COMMITTED_NARRATION_PENDING, NOW.plusSeconds(3));

        assertEquals(6, operation.getOperationVersion());
        assertEquals(4L, operation.getCommittedRevision());
        // The base revision is fixed at acceptance; only the commit records a new one.
        assertEquals(3, operation.getBaseRevision());
        assertEquals(NOW.plusSeconds(3), operation.getUpdatedAt());
        assertEquals(NOW, operation.getCreatedAt());
    }

    /** COMMITTED is reachable only through recordCommit, from COMMITTING, with a newer revision. */
    @Test
    void guardsTheCommitStep() {
        CampaignOperation operation = CampaignOperation.accept(UUID.randomUUID(), "k1", HASH, "explore", 3, NOW);
        assertThrows(IllegalArgumentException.class, () -> operation.transitionTo(OperationStatus.COMMITTED, NOW));
        assertThrows(IllegalStateException.class, () -> operation.recordCommit(4, "{}", NOW));
        operation.transitionTo(OperationStatus.PLANNING, NOW);
        operation.transitionTo(OperationStatus.VALIDATING, NOW);
        operation.transitionTo(OperationStatus.COMMITTING, NOW);
        assertThrows(IllegalArgumentException.class, () -> operation.recordCommit(3, "{}", NOW));
        assertThrows(IllegalArgumentException.class, () -> operation.recordCommit(4, "[]", NOW));
        // Failed attempts leave the entry untouched.
        assertEquals(OperationStatus.COMMITTING, operation.getStatus());
        assertEquals(3, operation.getOperationVersion());
    }

    /** Illegal transitions are refused and do not change the entry. */
    @Test
    void refusesIllegalTransitions() {
        CampaignOperation operation = CampaignOperation.accept(UUID.randomUUID(), "k1", HASH, "explore", 0, NOW);
        assertThrows(IllegalStateException.class, () -> operation.transitionTo(OperationStatus.VALIDATING, NOW));
        operation.transitionTo(OperationStatus.FAILED_BEFORE_COMMIT, NOW);
        assertThrows(IllegalStateException.class, () -> operation.transitionTo(OperationStatus.PLANNING, NOW));
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, operation.getStatus());
        assertEquals(1, operation.getOperationVersion());
    }

    /** Acceptance validates its inputs and payload hashes decide replay versus conflict. */
    @Test
    void validatesAcceptanceAndDetectsConflicts() {
        UUID campaign = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> CampaignOperation.accept(campaign, "", HASH, "x", 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> CampaignOperation.accept(campaign, "k", HASH.toUpperCase(), "x", 0, NOW));
        assertThrows(IllegalArgumentException.class, () -> CampaignOperation.accept(campaign, "k", HASH, "x", -1, NOW));
        assertThrows(NullPointerException.class, () -> CampaignOperation.accept(null, "k", HASH, "x", 0, NOW));
        CampaignOperation operation = CampaignOperation.accept(campaign, "k", HASH, "x", 0, NOW);
        assertTrue(operation.matchesPayload(HASH));
        assertFalse(operation.matchesPayload("f".repeat(64)));
    }
}
