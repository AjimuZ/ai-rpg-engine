package org.rpgengine.progression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Exact ownership of what a settled fight leaves behind (P13.4-A, docs/12 section 8). Pure and deterministic; nothing calls
 * it yet, and no pack declares what a participant carries.
 *
 * <p>Every held lot becomes exactly one {@link Lot} with exactly one holder, and nothing here creates, splits or merges units:
 * a claim moves a whole lot, so the units of each definition are the same before and after any sequence of claims
 * ({@link Loot#units}). Loot is a transfer of admitted holdings, never a creation; reward-table items are created only under
 * a challenge settlement ({@link ChallengeRewards}).
 *
 * <p>Rules:
 * <ul>
 *   <li>Only a lot of a {@link EncounterTemplate.Side#HOSTILE} participant left {@link EncounterState.Status#DEFEATED} by the
 *       verdict becomes {@link State#AVAILABLE}: collectible, owned by nobody until claimed (default defeated-enemy loot is
 *       not automatically owned). Equipped items of the fallen become available too, and their claim records the unequip.</li>
 *   <li>Every other lot stays {@link State#HELD} by its participant: allies, active, escaped and surrendered opponents keep
 *       theirs, so an item equipped on a living enemy can never reach the player's pack.</li>
 *   <li>Nothing opens after a {@code DEFEAT} or {@code PLAYER_SURRENDER}: the player did not keep the field.</li>
 *   <li>A lot is claimed once. A second claim is refused, also by the same recipient; a retried request is answered by the
 *       operation journal, not by claiming again.</li>
 * </ul>
 */
public final class EncounterLoot {
    /** Most lots one fight can leave. */
    public static final int MAX_LOTS = 128;

    /** Static entry point only. */
    private EncounterLoot() { }

    /** Who holds a lot. */
    public enum State {
        /** Still its participant's. */
        HELD,
        /** Left by a defeated hostile; owned by nobody until claimed. */
        AVAILABLE,
        /** Owned by its claimant. */
        CLAIMED
    }

    /** Why a claim is refused. */
    public enum Refusal {
        /** No lot has this ID. */
        UNKNOWN_LOT,
        /** Its participant still holds it. */
        NOT_AVAILABLE,
        /** It has already been claimed. */
        ALREADY_CLAIMED
    }

    /**
     * Where a participant stands at the verdict.
     *
     * @param participantId frozen participant ID; never {@link EncounterTemplate#PLAYER}
     * @param side its side
     * @param status its final status
     */
    public record Standing(String participantId, EncounterTemplate.Side side, EncounterState.Status status) {
        /** Require every part and refuse the player, whose own items are never loot. */
        public Standing {
            Objects.requireNonNull(participantId, "participantId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(status, "status");
            if (side == EncounterTemplate.Side.PLAYER || EncounterTemplate.PLAYER.equals(participantId)) {
                throw new IllegalArgumentException("the player's own items are never loot");
            }
        }
    }

    /**
     * One admitted holding a participant carried into the fight.
     *
     * @param lotId stable lot ID, unique within the fight
     * @param participantId the participant holding it
     * @param definitionId canonical item definition
     * @param quantity units, 1..{@link ChallengeRewards#MAX_QUANTITY}
     * @param equipped whether the participant wears or wields it
     */
    public record Holding(String lotId, String participantId, String definitionId, int quantity, boolean equipped) {
        /** Require canonical identities and a bounded quantity. */
        public Holding {
            ChallengeRewards.id(lotId, "lotId");
            Objects.requireNonNull(participantId, "participantId");
            ChallengeRewards.id(definitionId, "definitionId");
            if (quantity < 1 || quantity > ChallengeRewards.MAX_QUANTITY) {
                throw new IllegalArgumentException("a lot holds 1.." + ChallengeRewards.MAX_QUANTITY + " units");
            }
        }
    }

    /**
     * One lot and its holder.
     *
     * @param holding what it is and who carried it in
     * @param state who holds it now
     * @param owner the claimant's recipient key for {@link State#CLAIMED}, otherwise null
     */
    public record Lot(Holding holding, State state, String owner) {
        /** Require that only a claimed lot names an owner. */
        public Lot {
            Objects.requireNonNull(holding, "holding");
            Objects.requireNonNull(state, "state");
            if ((state == State.CLAIMED) != (owner != null)) {
                throw new IllegalArgumentException("exactly the claimed lots have an owner");
            }
        }
    }

    /**
     * The ownership move a claim commits: an existing instance changes holder, and no unit is created.
     *
     * @param sourceKey once-only identity {@code loot:<encounterId>:<lotId>}
     * @param fromParticipantId the defeated participant that carried it
     * @param recipientKey the claimant
     * @param definitionId canonical item definition
     * @param quantity every unit of the lot
     * @param unequipped whether it was equipped on the participant
     */
    public record Transfer(String sourceKey, String fromParticipantId, String recipientKey, String definitionId,
            int quantity, boolean unequipped) { }

    /** The answer to a claim. */
    public sealed interface Result permits Claimed, Refused { }

    /**
     * A committed claim.
     *
     * @param loot the loot after the claim
     * @param transfer the move to commit
     */
    public record Claimed(Loot loot, Transfer transfer) implements Result { }

    /**
     * A refused claim; the loot is unchanged.
     *
     * @param refusal why
     */
    public record Refused(Refusal refusal) implements Result { }

    /**
     * What one settled fight leaves, lot by lot.
     *
     * @param encounterId the frozen encounter's identity
     * @param lots every lot in holding order
     */
    public record Loot(UUID encounterId, List<Lot> lots) {
        /** Require an identity and freeze the lots. */
        public Loot {
            Objects.requireNonNull(encounterId, "encounterId");
            lots = List.copyOf(lots);
        }

        /** @return the lots anybody may claim, in holding order */
        public List<Lot> available() {
            return lots.stream().filter(lot -> lot.state() == State.AVAILABLE).toList();
        }

        /** @return units of every definition across all lots, whoever holds them; no claim changes it */
        public Map<String, Integer> units() {
            var units = new TreeMap<String, Integer>();
            lots.forEach(lot -> units.merge(lot.holding().definitionId(), lot.holding().quantity(), Integer::sum));
            return units;
        }

        /**
         * Claim a whole lot.
         *
         * @param lotId the lot
         * @param recipientKey the claimant, 1..256 characters
         * @return the loot after the claim and its transfer, or the refusal with the loot unchanged
         * @throws IllegalArgumentException for a malformed recipient
         */
        public Result claim(String lotId, String recipientKey) {
            String recipient = CanonicalValues.requireText(recipientKey, "recipientKey", 256);
            for (int index = 0; index < lots.size(); index++) {
                var lot = lots.get(index);
                if (!lot.holding().lotId().equals(lotId)) {
                    continue;
                }
                switch (lot.state()) {
                    case HELD -> {
                        return new Refused(Refusal.NOT_AVAILABLE);
                    }
                    case CLAIMED -> {
                        return new Refused(Refusal.ALREADY_CLAIMED);
                    }
                    case AVAILABLE -> {
                        var next = new ArrayList<>(lots);
                        next.set(index, new Lot(lot.holding(), State.CLAIMED, recipient));
                        var holding = lot.holding();
                        var transfer = new Transfer("loot:" + encounterId + ":" + holding.lotId(), holding.participantId(),
                                recipient, holding.definitionId(), holding.quantity(), holding.equipped());
                        return new Claimed(new Loot(encounterId, next), transfer);
                    }
                }
            }
            return new Refused(Refusal.UNKNOWN_LOT);
        }
    }

    /**
     * The standings of a settled state's non-player participants.
     *
     * @param state an encounter state at its verdict
     * @return each non-player participant's side and status, in roster order
     */
    public static List<Standing> standings(EncounterState state) {
        return state.participants().stream().filter(participant -> participant.side() != EncounterTemplate.Side.PLAYER)
                .map(participant -> new Standing(participant.id(), participant.side(), participant.status())).toList();
    }

    /**
     * Open a settled fight's loot.
     *
     * @param encounterId the frozen encounter's identity
     * @param outcome the verdict settlement finalized
     * @param standings every non-player participant at that verdict, each once
     * @param holdings what they carried in, at most {@link #MAX_LOTS}, lot IDs unique
     * @return every holding as one lot, available only when its holder is a defeated hostile and the player kept the field
     * @throws IllegalArgumentException for a duplicate participant or lot, a holding of an unknown participant or too many
     *         lots
     */
    public static Loot open(UUID encounterId, EncounterOutcome outcome, List<Standing> standings, List<Holding> holdings) {
        Objects.requireNonNull(outcome, "outcome");
        if (holdings.size() > MAX_LOTS) {
            throw new IllegalArgumentException("a fight leaves at most " + MAX_LOTS + " lots");
        }
        var byId = new HashMap<String, Standing>();
        for (Standing standing : standings) {
            if (byId.put(standing.participantId(), standing) != null) {
                throw new IllegalArgumentException("duplicate participant " + standing.participantId());
            }
        }
        boolean kept = outcome.kind() != EncounterOutcome.Kind.DEFEAT
                && outcome.kind() != EncounterOutcome.Kind.PLAYER_SURRENDER;
        var lotIds = new HashSet<String>();
        var lots = new ArrayList<Lot>();
        for (Holding holding : holdings) {
            if (!lotIds.add(holding.lotId())) {
                throw new IllegalArgumentException("duplicate lot " + holding.lotId());
            }
            var standing = byId.get(holding.participantId());
            if (standing == null) {
                throw new IllegalArgumentException("lot " + holding.lotId() + " names no participant of the fight");
            }
            boolean fallen = standing.side() == EncounterTemplate.Side.HOSTILE
                    && standing.status() == EncounterState.Status.DEFEATED;
            lots.add(new Lot(holding, kept && fallen ? State.AVAILABLE : State.HELD, null));
        }
        return new Loot(encounterId, lots);
    }
}
