package org.rpgengine.inference.contract;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.combat.CapabilitySnapshot;
import org.rpgengine.combat.EncounterFeasibility;
import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.FrozenEncounter;
import org.rpgengine.combat.ThreatBudget;
import org.rpgengine.rules.CombatProfile;

/**
 * Freezes the encounter planning decided and admits it only when it is feasible for the player (P13.3-A, docs/12 section 3
 * steps 6-9, QA-14). Pure: the same decision, context and entry facts always give the same result.
 *
 * <p><b>Guarantee.</b> An admitted encounter is frozen once, here, exactly as entry freezes one: {@link FrozenEncounter#freeze}
 * with the entry's identity, the template pack's profile, the player's committed facts and the content's combat forms, and no
 * opening strike. Its {@link EncounterFeasibility} verdict has no finding, so no declared objective is unreachable and no
 * hostile participant is a hidden hard counter. The admitted value holds that frozen definition; entry stores it as it is, and
 * {@link #unchanged} tells whether it still matches the committed state, so a fight never starts from stale assumptions and is
 * never quietly composed again.
 *
 * <p><b>Order.</b> A {@link EncounterFallback.Decision.Proposed} composition is admitted when feasible. Otherwise, and for a
 * {@link EncounterFallback.Decision.Fallback}, the result is the first offered option, in the context's order, whose authored
 * roster passes the threat check and whose frozen authored template is feasible, unchanged; or {@link Result.NoEncounter} when
 * none is: the engine never invents a fight to fill the gap. An infeasible proposal falls back with cause
 * {@link EncounterFallback.Cause#PROPOSAL_INVALID} and the code of its first finding as detail; a decision that was already a
 * fallback keeps its cause and detail.
 *
 * <p>Feasibility is judged after the proposal contract, not inside its validator, because it needs the player's frozen profile,
 * which depends on each option's pack profile and the content's combat forms, and because the encounter checked must be the
 * encounter entered. Its findings therefore get no repair attempt.
 */
public final class EncounterAdmission {
    /** Static entry point only. */
    private EncounterAdmission() {
    }

    /** Where an admitted encounter came from. */
    public enum Source {
        /** The runtime's composition. */
        PROPOSED,
        /** An authored template, unchanged. */
        AUTHORED
    }

    /**
     * The facts entry freezes with, read under the campaign lock at the same revision as the context's snapshot.
     *
     * @param encounterId the identity the encounter will be stored under; part of its frozen definition
     * @param profiles resolved combat profiles by pack ID, holding every offered template's pack
     * @param player the player's committed facts; the actor is the snapshot's player
     * @param library the content's combat forms
     */
    public record Entry(UUID encounterId, Map<String, CombatProfile> profiles, FrozenEncounter.PlayerFacts player,
            FrozenEncounter.Library library) {
        /** Require every value and copy the profiles. */
        public Entry {
            Objects.requireNonNull(encounterId, "encounterId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(library, "library");
            profiles = Map.copyOf(profiles);
        }
    }

    /** What planning hands to entry. */
    public sealed interface Result {
        /**
         * A frozen, feasible encounter.
         *
         * @param source the runtime's composition or an authored template
         * @param cause why the runtime's composition was not used, or null for {@link Source#PROPOSED}
         * @param detail fixed diagnostic code of that cause, or null for {@link Source#PROPOSED}; never output text
         * @param snapshotHash hash of the snapshot the encounter was budgeted against
         * @param option the option it was composed from
         * @param encounter the admitted template: the composed variant or the authored template
         * @param threat the admitted threat verdict of its hostile roster
         * @param frozen the frozen encounter entry stores, byte for byte
         */
        record Admitted(Source source, EncounterFallback.Cause cause, String detail, String snapshotHash,
                EncounterOption option, EncounterTemplate encounter, ThreatBudget.Verdict threat, FrozenEncounter frozen)
                implements Result {
            /** Require every value, a cause and detail exactly for an authored encounter, and admitted verdicts. */
            public Admitted {
                Objects.requireNonNull(source, "source");
                Objects.requireNonNull(snapshotHash, "snapshotHash");
                Objects.requireNonNull(option, "option");
                Objects.requireNonNull(encounter, "encounter");
                Objects.requireNonNull(threat, "threat");
                Objects.requireNonNull(frozen, "frozen");
                if ((source == Source.AUTHORED) != (cause != null) || (cause == null) != (detail == null)) {
                    throw new IllegalArgumentException("only an authored encounter names why the composition was not used");
                }
                if (!threat.admitted() || !EncounterFeasibility.check(frozen).admitted()) {
                    throw new IllegalArgumentException("an admitted encounter fits the budget and is feasible");
                }
            }
        }

        /**
         * No offered encounter is both within the budget and feasible: no fight starts.
         *
         * @param cause why the runtime's composition was not used
         * @param detail fixed diagnostic code, never output text
         */
        record NoEncounter(EncounterFallback.Cause cause, String detail) implements Result {
            /** Require every value. */
            public NoEncounter {
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }

    /**
     * Admit the encounter of one planning decision.
     *
     * @param decision the decision of {@link EncounterFallback#decide} on the same context
     * @param current the planning context read again before entry
     * @param entry the facts entry freezes with
     * @return the admitted frozen encounter, or no encounter
     * @throws IllegalArgumentException when the entry's player is not the snapshot's player or a pack has no profile
     * @throws IllegalStateException when the player's facts cannot be frozen under a profile, which the caller's rule gate
     *         excludes
     */
    public static Result admit(EncounterFallback.Decision decision, EncounterContext current, Entry entry) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(entry, "entry");
        boolean player = current.snapshot().actors().stream().anyMatch(actor ->
                actor.membership() == CapabilitySnapshot.Membership.PLAYER && actor.actorId().equals(entry.player().actorId()));
        if (!player) {
            throw new IllegalArgumentException("the entry facts describe another actor than the snapshot's player");
        }
        return switch (decision) {
            case EncounterFallback.Decision.Proposed proposed -> {
                var composed = proposed.composition();
                var frozen = freeze(composed.encounter(), entry);
                var verdict = EncounterFeasibility.check(frozen);
                if (verdict.admitted()) {
                    yield new Result.Admitted(Source.PROPOSED, null, null, composed.snapshotHash(), composed.option(),
                            composed.encounter(), composed.verdict(), frozen);
                }
                yield authored(EncounterFallback.Cause.PROPOSAL_INVALID, verdict.findings().getFirst().violation().code(),
                        current, entry);
            }
            case EncounterFallback.Decision.Fallback fallback -> authored(fallback.cause(), fallback.detail(), current, entry);
            case EncounterFallback.Decision.NoEncounter none -> new Result.NoEncounter(none.cause(), none.detail());
        };
    }

    /**
     * Whether an admitted encounter still describes the committed state: the snapshot hash is unchanged, its option is still
     * offered unchanged, and freezing its template again with the current entry facts gives the identical definition. Entry
     * stores the admitted frozen value only when this holds; otherwise planning runs again.
     *
     * @param admitted an admitted encounter
     * @param current the planning context read at entry
     * @param entry the facts read at entry, with the identity the encounter will be stored under
     * @return whether the admitted definition can be entered as it is
     */
    public static boolean unchanged(Result.Admitted admitted, EncounterContext current, Entry entry) {
        Objects.requireNonNull(admitted, "admitted");
        return admitted.snapshotHash().equals(current.snapshot().hash())
                && admitted.option().equals(current.option(admitted.option().template().id()))
                && freeze(admitted.encounter(), entry).definition().equals(admitted.frozen().definition());
    }

    /** The first offered authored template, unchanged, that fits the budget and is feasible; or no encounter. */
    private static Result authored(EncounterFallback.Cause cause, String detail, EncounterContext current, Entry entry) {
        var budget = current.budget();
        for (EncounterOption option : current.options()) {
            var threat = budget.check(option.entries(option.authored()));
            if (!threat.admitted()) {
                continue;
            }
            var frozen = freeze(option.template(), entry);
            if (EncounterFeasibility.check(frozen).admitted()) {
                return new Result.Admitted(Source.AUTHORED, cause, detail, current.snapshot().hash(), option,
                        option.template(), threat, frozen);
            }
        }
        return new Result.NoEncounter(cause, detail);
    }

    /** Freeze a template exactly as entry would, without an opening strike. */
    private static FrozenEncounter freeze(EncounterTemplate template, Entry entry) {
        var profile = entry.profiles().get(template.pack());
        if (profile == null) {
            throw new IllegalArgumentException("no combat profile for pack " + template.pack());
        }
        return FrozenEncounter.freeze(entry.encounterId(), template, profile, entry.player(), null, entry.library());
    }
}
