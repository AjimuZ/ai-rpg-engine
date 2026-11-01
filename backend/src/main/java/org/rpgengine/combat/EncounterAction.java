package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One declared social or scene action of an encounter template (P11.2, docs/11 section 7.1). Words alone never
 * change a fight: a request to persuade, intimidate, negotiate, distract or ask for a tactical benefit, or an
 * attempt to change the surroundings, is legal only when the frozen encounter declares it, and it then costs
 * its declared action points like any other action.
 *
 * <p>A {@link Kind#SOCIAL} action addresses one participant on the declared {@link Recipients} side who can be
 * reached by voice (see {@link TacticalRules#canAddress}). Its attempt is recorded. Since P11.5 it may declare a
 * {@link Negotiation.Check}, and a negotiation addressing hostiles may declare the {@link #terms} it proposes and a
 * {@link #counteroffer}; {@link Negotiation} decides the response. A {@link Kind#SCENE} action changes one existing
 * passage between two zones (for example closing a gate): it is usable only from its {@link #zone} when one is
 * declared, and only while the passage is not already in the requested state, so a no-op is refused instead of charged.
 *
 * @param id stable action ID, unique within the template
 * @param kind social or scene
 * @param intent social intent; null for a scene action
 * @param recipients side a social action may address; null for a scene action
 * @param cost action points, at least one (a normal action never costs zero)
 * @param zone zone the acting participant must stand in for a scene action, or null when usable anywhere
 * @param passage the passage a scene action changes; null for a social action
 * @param check the check a social action declares, or null when it needs none (P11.5)
 * @param terms what a negotiation proposes, or null when the action seeks no agreement (P11.5)
 * @param counteroffer what the recipient answers with short of the proposed terms, or null (P11.5)
 */
public record EncounterAction(String id, Kind kind, Intent intent, Recipients recipients, int cost, String zone,
        Passage passage, Negotiation.Check check, Negotiation.Terms terms, Negotiation.Terms counteroffer) {
    /** Most declared actions of one template. */
    public static final int MAX_ACTIONS = 8;

    /** Action without a check or terms, the shape used before P11.5. */
    public EncounterAction(String id, Kind kind, Intent intent, Recipients recipients, int cost, String zone, Passage passage) {
        this(id, kind, intent, recipients, cost, zone, passage, null, null, null);
    }

    /** Action families of docs/11 section 7.1. */
    public enum Kind {
        /** Talk with a mechanical purpose; addresses one participant. */
        SOCIAL,
        /** A bounded interaction with the surroundings. */
        SCENE;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Declared purpose of a social action; the closed list of docs/11 section 7.1. */
    public enum Intent {
        PERSUADE, INTIMIDATE, NEGOTIATE, DISTRACT, REQUEST;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Which participants a social action may address, relative to the player's side. */
    public enum Recipients {
        /** Only hostile participants. */
        HOSTILE,
        /** Only allied participants. */
        ALLY,
        /** Any other participant. */
        ANY;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether a participant of this side may be addressed.
         *
         * @param side the recipient's side
         * @return true when the policy admits it; the player never addresses itself
         */
        public boolean admits(EncounterTemplate.Side side) {
            return switch (this) {
                case HOSTILE -> side == EncounterTemplate.Side.HOSTILE;
                case ALLY -> side == EncounterTemplate.Side.ALLY;
                case ANY -> side != EncounterTemplate.Side.PLAYER;
            };
        }
    }

    /**
     * The passage between two zones a scene action sets. It covers every declared directed edge between the two
     * zones, in either direction, so closing a two-way gate closes both ways.
     *
     * @param from one zone of the passage
     * @param to the other zone
     * @param blocked state the action leaves the passage in
     */
    public record Passage(String from, String to, boolean blocked) {
        /** Require two different zones. */
        public Passage {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("a passage joins two different zones");
            }
        }

        /** @return whether the directed edge {@code a -> b} belongs to this passage */
        public boolean covers(String a, String b) {
            return a.equals(from) && b.equals(to) || a.equals(to) && b.equals(from);
        }
    }

    /** Check the shape of each kind. */
    public EncounterAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (cost < 1) {
            throw new IllegalArgumentException("a declared action costs at least one action point");
        }
        if (kind == Kind.SOCIAL && (intent == null || recipients == null || passage != null || zone != null)) {
            throw new IllegalArgumentException("a social action has an intent and recipients and no passage or zone");
        }
        if (kind == Kind.SCENE && (passage == null || intent != null || recipients != null || check != null || terms != null)) {
            throw new IllegalArgumentException("a scene action has a passage and no intent, recipients, check or terms");
        }
        if (terms != null && (intent != Intent.NEGOTIATE || recipients != Recipients.HOSTILE)) {
            throw new IllegalArgumentException("only a negotiation addressing hostiles proposes terms");
        }
        if (counteroffer != null && (terms == null || counteroffer.id().equals(terms.id()))) {
            throw new IllegalArgumentException("a counteroffer answers proposed terms and differs from them");
        }
    }

    /**
     * Freeze what depends on the player: its rank in the skill a declared check adds. Everything else stays as declared.
     *
     * @param skills the player's skill ranks by canonical definition
     * @return the action as this player's encounter stores it
     */
    EncounterAction forPlayer(Map<String, Long> skills) {
        return check == null ? this
                : new EncounterAction(id, kind, intent, recipients, cost, zone, passage, check.forPlayer(skills), terms, counteroffer);
    }

    /** @return canonical frozen form, part of the encounter definition; an action without a check or terms keeps its P11.2 form */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("kind", kind.wire());
        form.put("cost", cost);
        if (kind == Kind.SOCIAL) {
            form.put("intent", intent.wire());
            form.put("recipients", recipients.wire());
            if (check != null) {
                form.put("check", check.canonicalForm());
            }
            if (terms != null) {
                form.put("terms", terms.canonicalForm());
            }
            if (counteroffer != null) {
                form.put("counteroffer", counteroffer.canonicalForm());
            }
        } else {
            form.put("zone", zone);
            var change = new LinkedHashMap<String, Object>();
            change.put("from", passage.from());
            change.put("to", passage.to());
            change.put("blocked", passage.blocked());
            form.put("passage", change);
        }
        return form;
    }

    /**
     * Read the canonical form written by {@link #canonicalForm()}, as stored in a frozen definition.
     *
     * @param form parsed canonical object
     * @return the action
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed frozen form
     */
    public static EncounterAction fromCanonical(Map<?, ?> form) {
        var kind = Kind.valueOf(((String) form.get("kind")).toUpperCase(Locale.ROOT));
        int cost = ((Number) form.get("cost")).intValue();
        if (kind == Kind.SOCIAL) {
            return new EncounterAction((String) form.get("id"), kind,
                    Intent.valueOf(((String) form.get("intent")).toUpperCase(Locale.ROOT)),
                    Recipients.valueOf(((String) form.get("recipients")).toUpperCase(Locale.ROOT)), cost, null, null,
                    form.get("check") instanceof Map<?, ?> check ? Negotiation.Check.fromCanonical(check) : null,
                    form.get("terms") instanceof Map<?, ?> terms ? Negotiation.Terms.fromCanonical(terms) : null,
                    form.get("counteroffer") instanceof Map<?, ?> counter ? Negotiation.Terms.fromCanonical(counter) : null);
        }
        var passage = (Map<?, ?>) form.get("passage");
        return new EncounterAction((String) form.get("id"), kind, null, null, cost, (String) form.get("zone"),
                new Passage((String) passage.get("from"), (String) passage.get("to"),
                        Boolean.TRUE.equals(passage.get("blocked"))));
    }
}
