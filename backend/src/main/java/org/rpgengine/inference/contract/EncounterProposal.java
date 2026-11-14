package org.rpgengine.inference.contract;

import java.util.List;
import java.util.Objects;

import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.ThreatBudget;

/** Accepted result of an encounter-proposal task (P13.2-A): one checked composition or an explicit inability to compose. */
public sealed interface EncounterProposal {
    /**
     * A composition of one eligible template that the validator checked against the template and the threat budget of the
     * snapshot it names. It is a proposal: the caller still compares {@code snapshotHash} with a fresh reading before entry
     * ({@code EncounterFallback#decide}), and nothing is frozen or stored here.
     *
     * @param snapshotHash hash of the snapshot the composition was checked against
     * @param option the chosen eligible option
     * @param placements the chosen hostile participants and their starting zones, in output order
     * @param encounter the composed template variant
     * @param verdict the admitted threat verdict of the placements
     */
    record Composed(String snapshotHash, EncounterOption option, List<EncounterOption.Placement> placements,
            EncounterTemplate encounter, ThreatBudget.Verdict verdict) implements EncounterProposal {
        /** Require every value and freeze the placements. */
        public Composed {
            Objects.requireNonNull(snapshotHash, "snapshotHash");
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(encounter, "encounter");
            Objects.requireNonNull(verdict, "verdict");
            placements = List.copyOf(placements);
        }
    }

    /**
     * The runtime could not compose; the caller uses the deterministic fallback.
     *
     * @param reasonCode {@code no-fitting-roster} or {@code missing-information}
     */
    record CannotCompose(String reasonCode) implements EncounterProposal {
        /** Require the reason. */
        public CannotCompose {
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }
}
