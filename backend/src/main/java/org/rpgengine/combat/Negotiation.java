package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.rpgengine.rules.CombatProfile;

/**
 * The declared social checks and negotiation rules of an encounter (P11.5, docs/11 sections 7.1 and 13, QA-11, QA-13). Pure
 * values and one pure decision: nothing here reads text, so no words can move a negotiation, and the same recorded roll and
 * frozen action always give the same response.
 *
 * <p>The path of a negotiation:
 * <ol>
 *   <li>The player attempts a declared social action at its declared cost; a declared {@link Check} draws recorded dice.</li>
 *   <li>{@link #respond} decides the recipient's response. A refusal changes nothing else. Any other response only puts
 *       declared {@link Terms} on the table as an {@link Offer}: it never ends a fight or binds the player.</li>
 *   <li>The player answers that offer by its identity with an explicit command. Only an acceptance applies its terms.</li>
 * </ol>
 */
public final class Negotiation {
    /** Identity prefix of an offer, followed by the encounter row version of the operation that made it. */
    private static final String OFFER_PREFIX = "offer-";

    /** Static rules only. */
    private Negotiation() {
    }

    /**
     * A declared check of a social action: {@code 2d10 + attribute + trained skill} against a fixed difficulty or the
     * recipient's effective Resistance, the same score and margin as an attack (docs/11 section 8). No situational attack
     * modifier applies, because an effect's attack modifier is about blows, not words.
     *
     * @param attribute the role the check adds ({@code force}, {@code finesse}, {@code insight} or {@code resolve})
     * @param skill canonical skill definition the check adds, or null for none
     * @param rank the player's rank in that skill, frozen at entry; zero without a skill and in an unfrozen template
     * @param difficulty fixed target number, or null to meet the recipient's Resistance
     */
    public record Check(String attribute, String skill, int rank, Integer difficulty) {
        /** Require a role, a rank only with its skill and a difficulty within its bound. */
        public Check {
            if (!CombatProfile.ROLES.contains(Objects.requireNonNull(attribute, "attribute"))) {
                throw new IllegalArgumentException("a check adds one of the ruleset's roles");
            }
            if (rank < 0 || rank > CombatProfile.ATTACK_SKILL_MAX || skill == null && rank != 0) {
                throw new IllegalArgumentException("a check's rank belongs to its skill");
            }
            if (difficulty != null && (difficulty < 0 || difficulty > CombatProfile.CHECK_DIFFICULTY_MAX)) {
                throw new IllegalArgumentException("a check's difficulty lies within 0.." + CombatProfile.CHECK_DIFFICULTY_MAX);
            }
        }

        /**
         * Freeze the rank the player holds, under the same cap as an attack's skill.
         *
         * @param skills the player's skill ranks by canonical definition
         * @return this check with its rank
         */
        Check forPlayer(Map<String, Long> skills) {
            long held = skill == null ? 0 : skills.getOrDefault(skill, 0L);
            return new Check(attribute, skill, Math.toIntExact(Math.min(held, CombatProfile.ATTACK_SKILL_MAX)), difficulty);
        }

        /** @return the frozen form; the skill and difficulty keys appear only when declared */
        Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("attribute", attribute);
            if (skill != null) {
                form.put("skill", skill);
                form.put("rank", rank);
            }
            if (difficulty != null) {
                form.put("difficulty", difficulty);
            }
            return form;
        }

        /**
         * Read a stored frozen check back.
         *
         * @param form parsed {@link #canonicalForm}
         * @return the check
         * @throws ClassCastException or {@link IllegalArgumentException} for a malformed form
         */
        static Check fromCanonical(Map<?, ?> form) {
            return new Check((String) form.get("attribute"), (String) form.get("skill"),
                    form.get("rank") == null ? 0 : ((Number) form.get("rank")).intValue(),
                    form.get("difficulty") == null ? null : ((Number) form.get("difficulty")).intValue());
        }

        /** @return what the player may know before the attempt; the difficulty can stand for a recipient's hidden value */
        Map<String, Object> publicForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("attribute", attribute);
            form.put("skill", skill);
            form.put("rank", rank);
            return form;
        }
    }

    /**
     * Binding terms the frozen encounter declares. The terms name what becomes true when the player accepts them, from a
     * closed list: prose cannot widen it.
     *
     * @param id stable terms ID, unique within its action
     * @param kind what accepting them does
     * @param cost action points of carrying them out, paid on acceptance; zero when accepting is only a decision
     */
    public record Terms(String id, Kind kind, int cost) {
        /** What accepted terms do. */
        public enum Kind {
            /** Hostilities end for the whole encounter, everyone standing: the outcome is an agreement. */
            TRUCE("truce"),
            /** The recipient leaves the fight as surrendered; other participants fight on. */
            RECIPIENT_SURRENDERS("recipientSurrenders"),
            /** The player surrenders, which ends the fight. */
            PLAYER_SURRENDERS("playerSurrenders");

            private final String wire;

            Kind(String wire) {
                this.wire = wire;
            }

            /** @return the pack wire name */
            public String wire() {
                return wire;
            }

            /**
             * Parse a pack wire name.
             *
             * @param wire one of the documented names
             * @return the kind
             * @throws IllegalArgumentException for any other text
             */
            public static Kind parse(String wire) {
                for (Kind kind : values()) {
                    if (kind.wire.equals(wire)) {
                        return kind;
                    }
                }
                throw new IllegalArgumentException("unknown terms kind " + wire);
            }
        }

        /** Require identities and a cost of at least zero. */
        public Terms {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            if (cost < 0) {
                throw new IllegalArgumentException("terms cannot pay the player");
            }
        }

        /** @return the frozen form, which is also what the player is shown: terms hold no hidden value */
        Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("id", id);
            form.put("kind", kind.wire());
            form.put("cost", cost);
            return form;
        }

        /**
         * Read stored frozen terms back.
         *
         * @param form parsed {@link #canonicalForm}
         * @return the terms
         * @throws ClassCastException or {@link IllegalArgumentException} for a malformed form
         */
        static Terms fromCanonical(Map<?, ?> form) {
            return new Terms((String) form.get("id"), Kind.parse((String) form.get("kind")), ((Number) form.get("cost")).intValue());
        }
    }

    /** The recipient's response to a negotiation attempt. */
    public enum Response {
        /** Nothing is on the table and the fight goes on. */
        REFUSED,
        /** The recipient puts its own declared terms on the table. */
        COUNTEROFFERED,
        /** The recipient is open to the terms the action proposed. */
        OFFERED
    }

    /**
     * Terms on the table, waiting for the player's explicit answer. Stored on the encounter row while they stand.
     *
     * @param id offer identity, unique within the encounter
     * @param action declared action that led to it
     * @param recipient participant who made it
     * @param terms what accepting does
     * @param response how the recipient answered: never a refusal
     */
    public record Offer(String id, String action, String recipient, Terms terms, Response response) {
        /** Require identities; a refusal puts nothing on the table. */
        public Offer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(terms, "terms");
            if (Objects.requireNonNull(response, "response") == Response.REFUSED) {
                throw new IllegalArgumentException("a refusal is no offer");
            }
        }

        /** @return the stored form */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("id", id);
            form.put("actionId", action);
            form.put("recipientId", recipient);
            form.put("terms", terms.canonicalForm());
            form.put("response", response.name());
            return form;
        }

        /**
         * Read a stored offer back.
         *
         * @param form parsed {@link #canonicalForm}
         * @return the offer
         * @throws ClassCastException or {@link IllegalArgumentException} for a malformed form
         */
        public static Offer fromCanonical(Map<?, ?> form) {
            return new Offer((String) form.get("id"), (String) form.get("actionId"), (String) form.get("recipientId"),
                    Terms.fromCanonical((Map<?, ?>) form.get("terms")), Response.valueOf((String) form.get("response")));
        }
    }

    /**
     * An accepted truce, which ends the fight as a negotiated end. Surrender terms need no such record, because the
     * participant's status already says it.
     *
     * @param offer the offer the player accepted
     * @param terms the accepted terms
     * @param recipient participant who offered them
     */
    public record Agreement(String offer, String terms, String recipient) {
        /** Require identities. */
        public Agreement {
            Objects.requireNonNull(offer, "offer");
            Objects.requireNonNull(terms, "terms");
            Objects.requireNonNull(recipient, "recipient");
        }

        /** @return the stored form */
        public Map<String, Object> canonicalForm() {
            var form = new LinkedHashMap<String, Object>();
            form.put("offerId", offer);
            form.put("termsId", terms);
            form.put("recipientId", recipient);
            return form;
        }

        /**
         * Read a stored agreement back.
         *
         * @param form parsed {@link #canonicalForm}
         * @return the agreement
         * @throws ClassCastException or {@link NullPointerException} for a malformed form
         */
        public static Agreement fromCanonical(Map<?, ?> form) {
            return new Agreement((String) form.get("offerId"), (String) form.get("termsId"), (String) form.get("recipientId"));
        }
    }

    /**
     * Decide a recipient's response from frozen data and the recorded roll.
     *
     * @param action the attempted social action
     * @param roll the result of its declared check, or null when it declares none
     * @return null when the action proposes no terms; {@link Response#REFUSED} for a failed check; for a success
     *         {@link Response#COUNTEROFFERED} when a counteroffer is declared and the margin is not strong, otherwise
     *         {@link Response#OFFERED}. Without a check the declared counteroffer stands, or else the proposed terms do.
     * @throws IllegalArgumentException when a declared check has no roll
     */
    public static Response respond(EncounterAction action, CombatMath.Roll roll) {
        if (action.check() != null && roll == null) {
            throw new IllegalArgumentException("a declared check needs its roll");
        }
        if (action.terms() == null) {
            return null;
        }
        if (roll != null && !roll.hit()) {
            return Response.REFUSED;
        }
        return action.counteroffer() == null || roll != null && roll.strong() ? Response.OFFERED : Response.COUNTEROFFERED;
    }

    /**
     * Build the offer a response puts on the table.
     *
     * @param action the attempted action
     * @param response its response, not a refusal
     * @param recipient the participant who responded
     * @param rowVersion the encounter row version this operation read: one operation makes at most one offer, and the
     *        version never recurs, so a stale answer can never reach later terms of the same action
     * @return the offer
     */
    static Offer offer(EncounterAction action, Response response, String recipient, long rowVersion) {
        var terms = response == Response.COUNTEROFFERED ? action.counteroffer() : action.terms();
        return new Offer(OFFER_PREFIX + rowVersion, action.id(), recipient, terms, response);
    }
}
