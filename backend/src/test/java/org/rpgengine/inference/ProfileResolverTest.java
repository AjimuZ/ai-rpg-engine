package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ProfileResolver.Refused;
import org.rpgengine.inference.ProfileResolver.Resolution;
import org.rpgengine.inference.ProfileResolver.Resolved;

/**
 * Profile resolution of P08.4 without any runtime: the owner's assignment, the backend match, the
 * catalog and allowlist gates, owner-approved degradation, {@code fixedQuality} and the absence of
 * inherited state between tasks.
 */
class ProfileResolverTest {
    private static final EffectiveSettings LOW = new EffectiveSettings("model-a", "low");
    private static final EffectiveSettings MEDIUM = new EffectiveSettings("model-a", "medium");
    private static final EffectiveSettings HIGH = new EffectiveSettings("model-b", "high");

    private final Map<InferenceSlot, SlotAssignment> owner = new EnumMap<>(InferenceSlot.class);
    private final SlotAssignments assignments = slot -> Optional.ofNullable(owner.get(slot));
    /** Discovered catalog of the fixture backend: three pairs, no more. */
    private final Predicate<EffectiveSettings> catalog = Set.of(LOW, MEDIUM, HIGH)::contains;

    private ProfileResolver resolver() {
        return new ProfileResolver(assignments, ModelAllowlist.empty(), Optional.empty());
    }

    private static ModelAllowlist allowlist(String backend, EffectiveSettings... pairs) {
        return ModelAllowlist.of(Map.of(backend, List.of(pairs)));
    }

    @Test
    void everySlotResolvesToItsOwnAssignedPairAndNothingIsInherited() {
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", LOW));
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", MEDIUM));
        owner.put(InferenceSlot.DELIBERATE, SlotAssignment.of("codex", HIGH));
        ProfileResolver resolver = resolver();

        // An expensive task first, then ordinary and cheap ones: each resolves from its own slot.
        assertThat(resolver.resolve(InferenceSlot.DELIBERATE, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.DELIBERATE, HIGH, null));
        assertThat(resolver.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, MEDIUM, null));
        assertThat(resolver.resolve(InferenceSlot.LIGHT, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.LIGHT, LOW, null));
    }

    @Test
    void anUnassignedSlotIsRefusedWithoutAnyDefaultModel() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", MEDIUM));
        assertThat(resolver().resolve(InferenceSlot.LIGHT, "codex", catalog)).isEqualTo(new Refused("slot-unassigned"));
    }

    @Test
    void aSlotOfAnotherBackendIsNeverReroutedToThisOne() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("claude", MEDIUM));
        assertThat(resolver().resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Refused("slot-backend-mismatch"));
    }

    @Test
    void aPairOutsideTheDiscoveredCatalogIsUnsupportedEvenWhenAssigned() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", new EffectiveSettings("model-z", "medium")));
        assertThat(resolver().resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Refused("slot-unsupported"));
        // Same model, an effort that model does not offer.
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", new EffectiveSettings("model-a", "high")));
        assertThat(resolver().resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Refused("slot-unsupported"));
    }

    @Test
    void aBackendWithoutACatalogNeedsTheOwnersAllowlistAsItsCatalog() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("claude", MEDIUM));
        assertThat(resolver().resolve(InferenceSlot.STANDARD, "claude", null))
                .isEqualTo(new Refused("catalog-unavailable"));

        ProfileResolver approved = new ProfileResolver(assignments, allowlist("claude", MEDIUM), Optional.empty());
        assertThat(approved.resolve(InferenceSlot.STANDARD, "claude", null))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, MEDIUM, null));

        ProfileResolver other = new ProfileResolver(assignments, allowlist("claude", HIGH), Optional.empty());
        assertThat(other.resolve(InferenceSlot.STANDARD, "claude", null)).isEqualTo(new Refused("slot-unsupported"));
    }

    @Test
    void anAllowlistNarrowsTheDiscoveredCatalogAndIsBoundToItsBackend() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", MEDIUM));
        // The catalog offers the pair, but the owner approved something else for this backend.
        ProfileResolver narrowed = new ProfileResolver(assignments, allowlist("codex", HIGH), Optional.empty());
        assertThat(narrowed.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Refused("slot-unsupported"));
        // An allowlist of another backend does not constrain this one.
        ProfileResolver unrelated = new ProfileResolver(assignments, allowlist("claude", HIGH), Optional.empty());
        assertThat(unrelated.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, MEDIUM, null));
        // The allowlist cannot make an undiscovered pair usable.
        ProfileResolver listedOnly = new ProfileResolver(assignments,
                allowlist("codex", MEDIUM, new EffectiveSettings("model-z", "low")), Optional.empty());
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", new EffectiveSettings("model-z", "low")));
        assertThat(listedOnly.resolve(InferenceSlot.LIGHT, "codex", catalog))
                .isEqualTo(new Refused("slot-unsupported"));
    }

    @Test
    void approvedAlternativesApplyInOrderAndAreReportedAsDegraded() {
        EffectiveSettings missing = new EffectiveSettings("model-z", "high");
        EffectiveSettings alsoMissing = new EffectiveSettings("model-y", "high");
        owner.put(InferenceSlot.DELIBERATE, new SlotAssignment("codex", missing, List.of(alsoMissing, HIGH, MEDIUM)));
        Resolution resolution = resolver().resolve(InferenceSlot.DELIBERATE, "codex", catalog);

        assertThat(resolution).isEqualTo(new Resolved(InferenceSlot.DELIBERATE, HIGH, missing));
        assertThat(((Resolved) resolution).degraded()).isTrue();
    }

    @Test
    void thePrimaryPairWinsWheneverItIsUsableAndExhaustedAlternativesFailClearly() {
        owner.put(InferenceSlot.DELIBERATE, new SlotAssignment("codex", HIGH, List.of(MEDIUM)));
        Resolution primary = resolver().resolve(InferenceSlot.DELIBERATE, "codex", catalog);
        assertThat(primary).isEqualTo(new Resolved(InferenceSlot.DELIBERATE, HIGH, null));
        assertThat(((Resolved) primary).degraded()).isFalse();

        Predicate<EffectiveSettings> nothing = pair -> false;
        assertThat(resolver().resolve(InferenceSlot.DELIBERATE, "codex", nothing))
                .isEqualTo(new Refused("slot-unsupported"));
    }

    @Test
    void alternativesAreFilteredByTheAllowlistToo() {
        owner.put(InferenceSlot.STANDARD, new SlotAssignment("codex", new EffectiveSettings("model-z", "low"),
                List.of(LOW, MEDIUM)));
        ProfileResolver resolver = new ProfileResolver(assignments, allowlist("codex", MEDIUM), Optional.empty());
        assertThat(resolver.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, MEDIUM, new EffectiveSettings("model-z", "low")));
    }

    @Test
    void fixedQualityRoutesEveryRequestThroughTheOwnersChosenSlot() {
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", LOW));
        owner.put(InferenceSlot.DELIBERATE, SlotAssignment.of("codex", HIGH));
        ProfileResolver fixed = new ProfileResolver(assignments, ModelAllowlist.empty(),
                Optional.of(InferenceSlot.DELIBERATE));

        for (InferenceSlot requested : InferenceSlot.values()) {
            assertThat(fixed.resolve(requested, "codex", catalog))
                    .isEqualTo(new Resolved(InferenceSlot.DELIBERATE, HIGH, null));
        }
    }

    @Test
    void fixedQualityNeverFallsBackToTheRequestedSlot() {
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", LOW));
        ProfileResolver fixed = new ProfileResolver(assignments, ModelAllowlist.empty(),
                Optional.of(InferenceSlot.DELIBERATE));
        assertThat(fixed.resolve(InferenceSlot.LIGHT, "codex", catalog)).isEqualTo(new Refused("slot-unassigned"));
    }

    @Test
    void ownerReassignmentTakesEffectOnTheNextResolution() {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", MEDIUM));
        ProfileResolver resolver = resolver();
        assertThat(resolver.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, MEDIUM, null));
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", HIGH));
        assertThat(resolver.resolve(InferenceSlot.STANDARD, "codex", catalog))
                .isEqualTo(new Resolved(InferenceSlot.STANDARD, HIGH, null));
    }

    @Test
    void assignmentsRejectUnknownBackendsDuplicatesAndTooManyAlternatives() {
        assertThatThrownBy(() -> SlotAssignment.of("somewhere", LOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotAssignment("codex", LOW, List.of(LOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotAssignment("codex", LOW, List.of(MEDIUM, MEDIUM)))
                .isInstanceOf(IllegalArgumentException.class);
        List<EffectiveSettings> tooMany = List.of(MEDIUM, HIGH, new EffectiveSettings("m1", "low"),
                new EffectiveSettings("m2", "low"), new EffectiveSettings("m3", "low"));
        assertThatThrownBy(() -> new SlotAssignment("codex", LOW, tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SlotAssignment("codex", LOW, List.of(MEDIUM, HIGH)).candidates())
                .containsExactly(LOW, MEDIUM, HIGH);
    }

    @Test
    void settingsNotationRejectsMalformedEntriesWithoutEchoingThem() {
        assertThat(EffectiveSettings.parse(" model-a@low ")).isEqualTo(LOW);
        for (String bad : List.of("secret-model", "a@b@c", "secret model@low", "secret-model@Low", "@low", "m@")) {
            assertThatThrownBy(() -> EffectiveSettings.parse(bad)).isInstanceOf(IllegalArgumentException.class)
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret"));
        }
    }
}
