package org.rpgengine.progression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Once-only challenge-source rewards (P13.4-A, docs/12 section 8): which reward settlements a recipient is owed when fights
 * end and quests end, given the source keys already in its {@link RewardSettlement} ledger. Pure and deterministic; nothing
 * calls it yet.
 *
 * <p>The challenge is the unit of reward, not the fight or the quest. A challenge pays its XP and reward-table items under the
 * single source key {@code challenge:<challengeSourceId>}, whichever claim reaches it first: a fight with an earning outcome,
 * or a completed quest that names the same challenge. Every later claim on that key, from the same fight entered again, an
 * alias template declaring the same source, the quest after the fight, a repeated journal view or a retried request, is
 * skipped as {@link Reason#ALREADY_SETTLED}. A quest's own milestone is a distinct source under the existing key grammar
 * {@code quest:<questId>:outcome}, so a quest can pay a separate objective in addition to the fight's XP.
 *
 * <p>The plan holds each source key at most once and never one the ledger already holds. The ledger's unique
 * (campaign, source key, recipient key) constraint and {@code ResolvedCommit}'s one-settlement-per-source rule enforce the
 * same identity again when a plan is committed, so a plan read from a stale ledger fails its commit instead of paying twice.
 */
public final class ChallengeRewards {
    /** Prefix of a challenge's settlement source key. */
    public static final String CHALLENGE_PREFIX = "challenge:";
    /** Most item lines in one reward declaration. */
    public static final int MAX_ITEMS = 16;
    /** Most units of one item line. */
    public static final int MAX_QUANTITY = 9999;
    /** Most claims in one plan. */
    public static final int MAX_CLAIMS = 64;
    /**
     * Outcomes that earn a challenge unless its source declares otherwise: the hostiles are out of the fight, a stake is met
     * or a truce is accepted. Surrender and agreement earn it without a death; an escape earns it only when declared.
     */
    public static final Set<EncounterOutcome.Kind> DEFAULT_POLICY = Collections.unmodifiableSet(EnumSet.of(
            EncounterOutcome.Kind.VICTORY, EncounterOutcome.Kind.HOSTILE_SURRENDER, EncounterOutcome.Kind.OBJECTIVE,
            EncounterOutcome.Kind.AGREEMENT));
    /** A local or dependency-qualified ({@code pack#id}) canonical ID; no colon, so source keys stay unambiguous. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}(#[a-z][a-z0-9._-]{0,63})?");

    /** Static planning entry point only. */
    private ChallengeRewards() { }

    /** Default challenge award sizes (docs/12 section 9). */
    public enum Magnitude {
        /** A minor meaningful challenge. */
        MINOR(10),
        /** A standard challenge. */
        STANDARD(20),
        /** A major challenge. */
        MAJOR(35);

        private final int xp;

        Magnitude(int xp) {
            this.xp = xp;
        }

        /** @return the XP this magnitude awards */
        public int xp() {
            return xp;
        }
    }

    /** Why a claim, or one part of it, settles nothing. */
    public enum Reason {
        /** The outcome or quest ending does not earn the challenge under its policy. */
        NOT_EARNED,
        /** The ledger, or an earlier claim of the same plan, already holds this source key for the recipient. */
        ALREADY_SETTLED
    }

    /**
     * One rewarded item line, created for the recipient under the settlement's source key.
     *
     * @param definitionId canonical item definition
     * @param quantity units, 1..{@link #MAX_QUANTITY}
     */
    public record Item(String definitionId, int quantity) {
        /** Require a canonical definition and a bounded quantity. */
        public Item {
            id(definitionId, "definitionId");
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                throw new IllegalArgumentException("an item reward gives 1.." + MAX_QUANTITY + " units");
            }
        }
    }

    /**
     * An encounter's declared reward source.
     *
     * @param challengeSourceId the challenge's identity; templates and quests naming the same ID are one challenge
     * @param magnitude the XP it awards
     * @param earnedBy outcomes that earn it; never {@code DEFEAT} or {@code PLAYER_SURRENDER}, which meet no objective
     * @param items reward-table items it creates, distinct definitions, at most {@link #MAX_ITEMS}
     */
    public record Source(String challengeSourceId, Magnitude magnitude, Set<EncounterOutcome.Kind> earnedBy,
            List<Item> items) {
        /** A source under the {@link #DEFAULT_POLICY} without items. */
        public Source(String challengeSourceId, Magnitude magnitude) {
            this(challengeSourceId, magnitude, DEFAULT_POLICY, List.of());
        }

        /** Validate the identity, the policy and the items, and freeze them. */
        public Source {
            id(challengeSourceId, "challengeSourceId");
            Objects.requireNonNull(magnitude, "magnitude");
            if (earnedBy.isEmpty() || earnedBy.contains(EncounterOutcome.Kind.DEFEAT)
                    || earnedBy.contains(EncounterOutcome.Kind.PLAYER_SURRENDER)) {
                throw new IllegalArgumentException("a challenge is earned by at least one outcome and never by a defeat or a "
                        + "surrender of the player");
            }
            earnedBy = Collections.unmodifiableSet(EnumSet.copyOf(earnedBy));
            items = itemLines(items);
        }

        /** @return the settlement source key of this challenge */
        public String key() {
            return CHALLENGE_PREFIX + challengeSourceId;
        }

        /**
         * @param outcome a decided verdict
         * @return whether that verdict earns this challenge
         */
        public boolean earns(EncounterOutcome outcome) {
            return earnedBy.contains(outcome.kind());
        }
    }

    /** One ending that may pay rewards. */
    public sealed interface Claim permits Fight, Quest { }

    /**
     * A settled fight.
     *
     * @param encounterId the frozen encounter's identity, recorded as the settlement's origin
     * @param source the encounter's declared reward source
     * @param outcome the verdict settlement finalized
     */
    public record Fight(UUID encounterId, Source source, EncounterOutcome outcome) implements Claim {
        /** Require every part; a fight still in progress has no verdict and cannot claim. */
        public Fight {
            Objects.requireNonNull(encounterId, "encounterId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * An ended quest.
     *
     * @param questId canonical quest ID
     * @param outcomeId the outcome it ended with
     * @param completed whether it ended completed; only a completed quest earns its challenge
     * @param challenge the reward source of the challenge this quest shares with a fight, or null
     * @param milestone XP of the quest's own milestone, or null for none
     * @param items items of the quest's own milestone, possibly none
     */
    public record Quest(String questId, String outcomeId, boolean completed, Source challenge, Magnitude milestone,
            List<Item> items) implements Claim {
        /** Require identities and freeze the milestone items. */
        public Quest {
            id(questId, "questId");
            id(outcomeId, "outcomeId");
            items = itemLines(items);
        }

        /** @return the source key of the quest's own milestone, the grammar quest endings already settle under (P10.3) */
        public String milestoneKey() {
            return "quest:" + questId + ":outcome";
        }
    }

    /**
     * One settlement to write.
     *
     * @param sourceKey reward source identity
     * @param recipientKey recipient identity
     * @param xp XP granted, 0 or more
     * @param items items created for the recipient under {@code sourceKey}
     * @param claim index of the claim that settles it
     * @param form canonical rewards object for the ledger row: {@code xp}, {@code items}, the origin and, for a challenge,
     *        {@code challengeSourceId}
     */
    public record Settlement(String sourceKey, String recipientKey, int xp, List<Item> items, int claim,
            Map<String, Object> form) {
        /** Freeze the parts. */
        public Settlement {
            items = List.copyOf(items);
            form = Collections.unmodifiableMap(new TreeMap<>(form));
        }
    }

    /**
     * A claim part that settles nothing.
     *
     * @param claim index of the claim
     * @param sourceKey the source key it would have settled
     * @param reason why it settles nothing
     */
    public record Skip(int claim, String sourceKey, Reason reason) { }

    /**
     * The settlements a set of endings owes one recipient.
     *
     * @param settlements settlements in claim order, a quest's milestone before its challenge; each source key once
     * @param skips claim parts that settle nothing, in the same order
     */
    public record Plan(List<Settlement> settlements, List<Skip> skips) {
        /** Freeze the lists. */
        public Plan {
            settlements = List.copyOf(settlements);
            skips = List.copyOf(skips);
        }

        /** @return the XP of every settlement */
        public int xp() {
            return settlements.stream().mapToInt(Settlement::xp).sum();
        }
    }

    /**
     * Decide the settlements owed for these endings.
     *
     * @param recipientKey the recipient, 1..256 characters, for example the player's actor identity
     * @param settled source keys the recipient's ledger already holds
     * @param claims endings in the order they are settled, at most {@link #MAX_CLAIMS}
     * @return each owed settlement once, and every claim part that pays nothing with its reason
     * @throws IllegalArgumentException for a malformed recipient or too many claims
     */
    public static Plan plan(String recipientKey, Set<String> settled, List<Claim> claims) {
        String recipient = CanonicalValues.requireText(recipientKey, "recipientKey", 256);
        if (claims.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException("a plan settles at most " + MAX_CLAIMS + " claims");
        }
        var taken = new HashSet<>(settled);
        var settlements = new ArrayList<Settlement>();
        var skips = new ArrayList<Skip>();
        for (int index = 0; index < claims.size(); index++) {
            switch (Objects.requireNonNull(claims.get(index), "claim")) {
                case Fight fight -> {
                    var origin = new TreeMap<String, Object>();
                    origin.put("kind", "encounter");
                    origin.put("encounterId", fight.encounterId().toString());
                    origin.put("outcome", fight.outcome().kind().name());
                    challenge(index, recipient, fight.source(), fight.source().earns(fight.outcome()), origin, taken,
                            settlements, skips);
                }
                case Quest quest -> {
                    var origin = new TreeMap<String, Object>();
                    origin.put("kind", "quest");
                    origin.put("questId", quest.questId());
                    origin.put("outcomeId", quest.outcomeId());
                    int xp = quest.milestone() == null ? 0 : quest.milestone().xp();
                    if (xp > 0 || !quest.items().isEmpty()) {
                        String key = quest.milestoneKey();
                        if (taken.add(key)) {
                            var form = new TreeMap<String, Object>();
                            form.put("origin", origin);
                            settlements.add(settlement(key, recipient, xp, quest.items(), index, form));
                        } else {
                            skips.add(new Skip(index, key, Reason.ALREADY_SETTLED));
                        }
                    }
                    if (quest.challenge() != null) {
                        challenge(index, recipient, quest.challenge(), quest.completed(), origin, taken, settlements, skips);
                    }
                }
            }
        }
        return new Plan(settlements, skips);
    }

    /** Settle a challenge once if earned, otherwise record why not. */
    private static void challenge(int index, String recipient, Source source, boolean earned, Map<String, Object> origin,
            Set<String> taken, List<Settlement> settlements, List<Skip> skips) {
        String key = source.key();
        if (!earned) {
            skips.add(new Skip(index, key, Reason.NOT_EARNED));
            return;
        }
        if (!taken.add(key)) {
            skips.add(new Skip(index, key, Reason.ALREADY_SETTLED));
            return;
        }
        var form = new TreeMap<String, Object>();
        form.put("challengeSourceId", source.challengeSourceId());
        form.put("origin", origin);
        settlements.add(settlement(key, recipient, source.magnitude().xp(), source.items(), index, form));
    }

    /** Build a settlement whose form also carries its XP and items. */
    private static Settlement settlement(String key, String recipient, int xp, List<Item> items, int index,
            Map<String, Object> form) {
        form.put("xp", xp);
        var lines = new ArrayList<Object>();
        for (Item item : items) {
            var line = new TreeMap<String, Object>();
            line.put("definitionId", item.definitionId());
            line.put("quantity", item.quantity());
            lines.add(line);
        }
        form.put("items", lines);
        return new Settlement(key, recipient, xp, items, index, form);
    }

    /** Bound and freeze reward items, one line per definition. */
    private static List<Item> itemLines(List<Item> items) {
        var copy = List.copyOf(items);
        if (copy.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a reward declares at most " + MAX_ITEMS + " item lines");
        }
        var definitions = new HashSet<String>();
        for (Item item : copy) {
            if (!definitions.add(item.definitionId())) {
                throw new IllegalArgumentException("a reward names each item definition once");
            }
        }
        return copy;
    }

    /** Fail unless a value is a canonical ID. */
    static String id(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a canonical ID");
        }
        return value;
    }
}
