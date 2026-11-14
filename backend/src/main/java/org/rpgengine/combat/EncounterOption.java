package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One authored encounter template that encounter planning may offer for a scene, at the level its hostile participants are
 * costed at (P13.2-A, docs/12 section 3): the bounds a composed roster must stay inside, the composition of a variant from
 * chosen hostile participants and starting zones, and the deterministic safe fallback among several options.
 *
 * <p>A composition only selects among what the author declared: which of the template's hostile participants take part and
 * in which declared zone each starts. It never adds a participant or changes statistics, attacks, reactions, conditions,
 * allies, objectives, escape zones, the zone graph, the opening policy or the declared actions. It cannot start a hostile
 * participant in an escape zone, so the escape approach the author declared stays open.
 *
 * @param template the eligible authored template, already validated by the pack compiler
 * @param level the level every hostile participant of the template is costed at,
 *        {@link CapabilitySnapshot#LEVEL_MIN}..{@link CapabilitySnapshot#LEVEL_MAX}; supplied by the caller until advancement
 *        provides a level source (P13.4)
 */
public record EncounterOption(EncounterTemplate template, int level) {
    /**
     * One hostile participant of the template and the declared zone it starts in.
     *
     * @param participantId the template participant's ID
     * @param zone its starting zone
     */
    public record Placement(String participantId, String zone) {
        /** Require both values. */
        public Placement {
            Objects.requireNonNull(participantId, "participantId");
            Objects.requireNonNull(zone, "zone");
        }
    }

    /**
     * A roster that passed the threat check.
     *
     * @param option the option it was composed from
     * @param encounter the template to enter: the authored one, or a composed variant
     * @param verdict the admitted verdict of its hostile roster
     */
    public record Admitted(EncounterOption option, EncounterTemplate encounter, ThreatBudget.Verdict verdict) {
        /** Require every value and an admitted verdict. */
        public Admitted {
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(encounter, "encounter");
            if (!verdict.admitted()) {
                throw new IllegalArgumentException("an admitted roster has no finding");
            }
        }
    }

    /** Require the template and a level the threat heuristic accepts. */
    public EncounterOption {
        Objects.requireNonNull(template, "template");
        if (level < CapabilitySnapshot.LEVEL_MIN || level > CapabilitySnapshot.LEVEL_MAX) {
            throw new IllegalArgumentException("level must be within " + CapabilitySnapshot.LEVEL_MIN + ".."
                    + CapabilitySnapshot.LEVEL_MAX);
        }
    }

    /** @return the template's hostile participants in authored order */
    public List<EncounterTemplate.Participant> hostiles() {
        return template.participants().stream().filter(p -> p.side() == EncounterTemplate.Side.HOSTILE).toList();
    }

    /** @return the declared zones a hostile participant may start in: every zone except the escape zones, sorted */
    public Set<String> startZones() {
        var zones = new TreeSet<>(template.zones().zones().keySet());
        zones.removeAll(template.escapeZones());
        return Collections.unmodifiableSet(zones);
    }

    /**
     * @param budget the party's threat budget
     * @return the most hostile participants a composition may hold: the budget's limit, and never more than the template's own
     *         limit of the player, its allies and {@link EncounterTemplate#EXTRA_HOSTILES}
     */
    public int hostileLimit(ThreatBudget budget) {
        int allies = template.participants().size() - hostiles().size();
        return Math.min(budget.hostileLimit(), 1 + allies + EncounterTemplate.EXTRA_HOSTILES);
    }

    /** @return every hostile participant at its authored zone, in authored order: the template's own roster */
    public List<Placement> authored() {
        return hostiles().stream().map(p -> new Placement(p.id(), p.zone())).toList();
    }

    /**
     * Zones whose declared capacity a composition would exceed at the start: the player in its zone, the template's allies in
     * their authored zones and the placed hostile participants. A zone of capacity 0 is unbounded.
     *
     * @param placements hostile placements in declared zones
     * @return the overfilled zone IDs, sorted; empty when every zone holds its occupants
     * @throws IllegalArgumentException for a zone the template does not declare
     */
    public Set<String> overfilled(List<Placement> placements) {
        var occupancy = new LinkedHashMap<String, Integer>(Map.of(template.playerZone(), 1));
        template.participants().stream().filter(p -> p.side() == EncounterTemplate.Side.ALLY)
                .forEach(ally -> occupancy.merge(ally.zone(), 1, Integer::sum));
        placements.forEach(placement -> occupancy.merge(placement.zone(), 1, Integer::sum));
        var overfilled = new TreeSet<String>();
        occupancy.forEach((zone, count) -> {
            var declared = template.zones().zones().get(zone);
            if (declared == null) {
                throw new IllegalArgumentException("zone " + zone + " is not declared");
            }
            if (declared.capacity() > 0 && count > declared.capacity()) {
                overfilled.add(zone);
            }
        });
        return Collections.unmodifiableSet(overfilled);
    }

    /**
     * Threat-check entries of placements, in placement order, under each participant's ID and catalog tier at this option's
     * level.
     *
     * @param placements placements of the template's hostile participants
     * @return entries for {@link ThreatBudget#check}
     * @throws IllegalArgumentException for a participant that is not a hostile participant of the template
     */
    public List<ThreatBudget.Entry> entries(List<Placement> placements) {
        return placements.stream().map(placement -> new ThreatBudget.Entry(placement.participantId(),
                hostile(placement.participantId()).threat(), level)).toList();
    }

    /**
     * Compose the variant a set of placements describes. Participants keep the authored order: the allies as authored, and of
     * the hostile participants only the placed ones, each in its placed zone. Everything else is the template's.
     *
     * @param placements one placement per chosen hostile participant, in any order
     * @return the variant, validated by the {@link EncounterTemplate} constructor
     * @throws IllegalArgumentException for an unknown or repeated participant, a zone outside {@link #startZones()}, no
     *         placement, an overfilled zone or too many hostile participants for the template
     */
    public EncounterTemplate compose(List<Placement> placements) {
        var zones = new LinkedHashMap<String, String>();
        var allowed = startZones();
        for (Placement placement : placements) {
            hostile(placement.participantId());
            if (!allowed.contains(placement.zone())) {
                throw new IllegalArgumentException("zone " + placement.zone() + " is not a starting zone");
            }
            if (zones.put(placement.participantId(), placement.zone()) != null) {
                throw new IllegalArgumentException("participant " + placement.participantId() + " is placed twice");
            }
        }
        var participants = new ArrayList<EncounterTemplate.Participant>();
        for (EncounterTemplate.Participant participant : template.participants()) {
            if (participant.side() != EncounterTemplate.Side.HOSTILE) {
                participants.add(participant);
            } else if (zones.containsKey(participant.id())) {
                participants.add(new EncounterTemplate.Participant(participant.id(), participant.side(),
                        zones.get(participant.id()), participant.npc(), participant.threat(), participant.stats(),
                        participant.attacks(), participant.reactions(), participant.policies(), participant.conditions()));
            }
        }
        return new EncounterTemplate(template.id(), template.pack(), template.location(), template.zones(),
                template.playerZone(), template.escapeZones(), participants, template.objectives(), template.opening(),
                template.actions(), template.disengagement());
    }

    /**
     * The deterministic safe fallback of docs/12 section 3 step 7: the first option, in the order given, whose authored template,
     * unchanged, passes the threat check. Nothing is composed, drawn or invented, so the same options and budget always give the
     * same result.
     *
     * @param options eligible options in the caller's preference order
     * @param budget the party's threat budget
     * @return the admitted authored template, or null when none fits: the caller then reports that no valid encounter exists
     *         and starts no fight
     */
    public static Admitted fallback(List<EncounterOption> options, ThreatBudget budget) {
        for (EncounterOption option : options) {
            var verdict = budget.check(option.entries(option.authored()));
            if (verdict.admitted()) {
                return new Admitted(option, option.template(), verdict);
            }
        }
        return null;
    }

    /** @return the template's hostile participant with this ID */
    private EncounterTemplate.Participant hostile(String participantId) {
        var participant = template.participant(participantId);
        if (participant == null || participant.side() != EncounterTemplate.Side.HOSTILE) {
            throw new IllegalArgumentException("participant " + participantId + " is not a hostile participant of "
                    + template.id());
        }
        return participant;
    }
}
