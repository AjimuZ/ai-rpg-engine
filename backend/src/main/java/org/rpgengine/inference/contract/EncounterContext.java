package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

import org.rpgengine.combat.CapabilitySnapshot;
import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.ThreatBudget;

/**
 * Trusted input of one encounter-proposal task (P13.2-A, docs/12 section 3, docs/21 section 6): the party's capability snapshot,
 * the threat budget derived from it and the eligible authored templates a proposal may compose from.
 *
 * <p>The caller has already decided that an encounter is eligible here and which templates are compatible with the scene; the
 * contract never selects templates itself. Every option belongs to the snapshot's location, so a proposal cannot move the fight
 * elsewhere. The snapshot must have a contributing actor, because a party with none has no band to fit.
 *
 * @param binding identity, generation and locale of the task; the base revision is the revision the snapshot was read at, and
 *        its pack hash is the snapshot's
 * @param snapshot the party's capabilities and the selected difficulty
 * @param options eligible templates with their levels, 1..{@link #MAX_OPTIONS}, distinct by template ID, in the caller's
 *        preference order; the deterministic fallback tries them in this order
 */
public record EncounterContext(TaskBinding binding, CapabilitySnapshot snapshot, List<EncounterOption> options)
        implements ContractContext {
    /** Most eligible templates per task. */
    public static final int MAX_OPTIONS = 8;

    /**
     * Freeze and bound the context.
     *
     * @throws IllegalArgumentException for a snapshot of other content or without a contributing actor, no or too many
     *         options, two options of one template, or an option at another location
     */
    public EncounterContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.packHash().equals(binding.packHash())) {
            throw new IllegalArgumentException("the snapshot must be read against the task's pinned content");
        }
        if (snapshot.partySize() == 0) {
            throw new IllegalArgumentException("a party without a contributing actor cannot be planned for");
        }
        options = ContextValues.distinct(options, "options", 1, MAX_OPTIONS, option -> option.template().id());
        for (EncounterOption option : options) {
            if (!option.template().location().equals(snapshot.location())) {
                throw new IllegalArgumentException("template " + option.template().id() + " belongs to another location");
            }
        }
    }

    /** @return the threat budget of the snapshot */
    public ThreatBudget budget() {
        return ThreatBudget.of(snapshot);
    }

    /**
     * @param templateId a template ID
     * @return the supplied option of that template, or null
     */
    public EncounterOption option(String templateId) {
        return options.stream().filter(option -> option.template().id().equals(templateId)).findFirst().orElse(null);
    }

    /** @return no glossary: an encounter proposal has no human-facing output text */
    @Override
    public List<GlossaryTerm> glossary() {
        return List.of();
    }

    /** The encounter proposer's context holds privileged planning material by design; nothing is guarded from its audience. */
    @Override
    public DisclosureGuard guard() {
        return DisclosureGuard.none();
    }
}
