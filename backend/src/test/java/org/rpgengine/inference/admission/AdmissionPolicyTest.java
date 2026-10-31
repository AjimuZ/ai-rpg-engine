package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.AGING;
import static org.rpgengine.inference.admission.AdmissionFixtures.B;
import static org.rpgengine.inference.admission.AdmissionFixtures.C;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.MEDIUM;
import static org.rpgengine.inference.admission.AdmissionFixtures.SMALL;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.TTL;
import static org.rpgengine.inference.admission.AdmissionFixtures.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.scheduling.WorkClass;

/**
 * The pure admission policy: priority and aging, leases with grant, renewal, expiry and release,
 * batching of compatible requests, the no-overtaking rule, fairness across modalities and owners,
 * the choice of leases to take back, holds for unconfirmed leases and the refusal paths. No clock,
 * thread or database is involved: every time is passed in.
 */
class AdmissionPolicyTest {
    private static final Demand BIG_BUDGET = new Demand(1000, 1000);

    private final AdmissionFixtures fixtures = new AdmissionFixtures();

    /** Admit until the policy stops, returning the service order of request names. */
    private List<String> drain(AdmissionPolicy policy) {
        List<String> order = new ArrayList<>();
        for (var next = policy.admitNext(T0); next.isPresent(); next = policy.admitNext(T0)) {
            order.addAll(fixtures.names(next.get()));
        }
        return order;
    }

    private void accept(AdmissionPolicy policy, AdmissionRequest... requests) {
        for (AdmissionRequest request : requests) {
            assertThat(policy.submit(request)).isEqualTo(Submission.ACCEPTED);
        }
    }

    @Test
    void interactiveWorkGoesBeforeOlderBackgroundWorkEvenWhenOnlyOneModelFits() {
        var policy = new AdmissionPolicy(settings());
        var background = fixtures.request("plan", Modality.TEXT, WorkClass.CONTINUITY, A, "text-a", LARGE,
                T0.minus(Duration.ofHours(1)));
        var interactive = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        accept(policy, background, interactive);

        var first = policy.admitNext(T0).orElseThrow();
        assertThat(fixtures.names(first)).containsExactly("turn");
        assertThat(first.created()).isTrue();
        // The background model no longer fits next to the interactive one, so it keeps waiting.
        assertThat(policy.admitNext(T0)).isEmpty();
        assertThat(policy.waiting()).containsExactly(background);
    }

    @Test
    void backgroundWorkAgesByClassButNeverOvertakesInteractiveWork() {
        var policy = new AdmissionPolicy(settings(BIG_BUDGET, 4));
        var speculative = fixtures.request("old-speculation", Modality.TEXT, WorkClass.SPECULATIVE, A, "m-spec", SMALL,
                T0.minus(AGING.multipliedBy(30)));
        var preparation = fixtures.text("preparation", WorkClass.PREPARATION, A, "m-prep", SMALL);
        var interactive = fixtures.text("turn", WorkClass.INTERACTIVE, A, "m-turn", SMALL);
        accept(policy, speculative, preparation, interactive);

        // Aging lifts the speculative request to the continuity class, ahead of newer preparation work,
        // but the interactive request, submitted last, still goes first.
        assertThat(drain(policy)).containsExactly("turn", "old-speculation", "preparation");
    }

    @Test
    void aLeaseReservesCapacityUntilItExpiresIsRenewedOrReleased() {
        var policy = new AdmissionPolicy(settings());
        var first = fixtures.text("first", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        accept(policy, first);
        var lease = policy.admitNext(T0).orElseThrow().lease();

        assertThat(lease.expiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(lease.demand()).isEqualTo(LARGE);
        assertThat(policy.free()).isEqualTo(new Demand(40, 90));
        assertThat(policy.expire(T0.plus(TTL).minusSeconds(1))).isEmpty();

        // A renewal restarts the lifetime from now and never shortens it.
        assertThat(policy.renew(lease.id(), T0.plusSeconds(30)).orElseThrow().expiresAt()).isEqualTo(T0.plusSeconds(90));
        assertThat(policy.renew(lease.id(), T0.plusSeconds(10)).orElseThrow().expiresAt()).isEqualTo(T0.plusSeconds(90));
        assertThat(policy.expire(T0.plus(TTL))).isEmpty();

        List<Lease> expired = policy.expire(T0.plusSeconds(90));
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().members()).containsExactly(first.id());
        assertThat(policy.free()).isEqualTo(CAPACITY);
        assertThat(policy.leases()).isEmpty();
        assertThat(policy.renew(lease.id(), T0.plusSeconds(91))).as("an expired lease cannot be renewed").isEmpty();
        assertThat(policy.finish(first.id())).as("the member lost its admission").isEmpty();
    }

    @Test
    void anExpiredLeaseCannotBeRenewedBeforeItIsRemoved() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.text("first", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var lease = policy.admitNext(T0).orElseThrow().lease();

        assertThat(policy.renew(lease.id(), T0.plus(TTL))).isEmpty();
        assertThat(policy.free()).as("capacity stays reserved until expire() removes it").isEqualTo(new Demand(40, 90));
    }

    @Test
    void theLastMemberToFinishEndsTheLeaseAndFreesItsCapacity() {
        var policy = new AdmissionPolicy(settings());
        var r1 = fixtures.text("r1", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        var r2 = fixtures.text("r2", WorkClass.INTERACTIVE, B, "text-a", LARGE);
        accept(policy, r1, r2);
        var grant = policy.admitNext(T0).orElseThrow();
        assertThat(fixtures.names(grant)).containsExactly("r1", "r2");

        var partial = policy.finish(r1.id()).orElseThrow();
        assertThat(partial.closed()).isFalse();
        assertThat(partial.lease().members()).containsExactly(r2.id());
        assertThat(policy.free()).isEqualTo(new Demand(40, 90));
        assertThat(policy.finish(r1.id())).as("a member leaves once").isEmpty();

        var last = policy.finish(r2.id()).orElseThrow();
        assertThat(last.closed()).isTrue();
        assertThat(policy.free()).isEqualTo(CAPACITY);
        assertThat(policy.leases()).isEmpty();
    }

    @Test
    void compatibleWaitingRequestsShareOneLeaseUpToTheBatchLimit() {
        var policy = new AdmissionPolicy(settings(CAPACITY, 2));
        var r1 = fixtures.text("r1", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        var r2 = fixtures.text("r2", WorkClass.INTERACTIVE, B, "text-a", LARGE);
        var r3 = fixtures.text("r3", WorkClass.INTERACTIVE, C, "text-a", LARGE);
        var other = fixtures.text("other-model", WorkClass.INTERACTIVE, A, "text-b", LARGE);
        accept(policy, r1, other, r2, r3);

        var grant = policy.admitNext(T0).orElseThrow();
        assertThat(fixtures.names(grant)).as("two share the model, one copy of it is reserved").containsExactly("r1", "r2");
        assertThat(grant.created()).isTrue();
        assertThat(policy.free()).isEqualTo(new Demand(40, 90));
        // The lease is full and a second copy of the model does not fit, whichever request comes first.
        assertThat(policy.admitNext(T0)).isEmpty();
        assertThat(policy.waiting()).containsExactlyInAnyOrder(other, r3);
    }

    @Test
    void aRequestJoinsAResidentLeaseWithRoomAtNoExtraCapacity() {
        var policy = new AdmissionPolicy(settings());
        var r1 = fixtures.text("r1", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        accept(policy, r1);
        var lease = policy.admitNext(T0).orElseThrow().lease();
        var latecomer = fixtures.text("latecomer", WorkClass.CONTINUITY, B, "text-a", LARGE);
        accept(policy, latecomer);

        var joined = policy.admitNext(T0.plusSeconds(5)).orElseThrow();
        assertThat(joined.created()).isFalse();
        assertThat(joined.lease().id()).isEqualTo(lease.id());
        assertThat(joined.lease().members()).containsExactly(r1.id(), latecomer.id());
        assertThat(policy.free()).as("the model is counted once").isEqualTo(new Demand(40, 90));
        // Joining does not extend the lease: the holder renews it.
        assertThat(joined.lease().expiresAt()).isEqualTo(lease.expiresAt());
    }

    @Test
    void aFullLeaseAdmitsTheNextRequestOnlyWhenAMemberLeaves() {
        var policy = new AdmissionPolicy(settings(CAPACITY, 2));
        var r1 = fixtures.text("r1", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        var r2 = fixtures.text("r2", WorkClass.INTERACTIVE, B, "text-a", LARGE);
        var r3 = fixtures.text("r3", WorkClass.INTERACTIVE, C, "text-a", LARGE);
        accept(policy, r1, r2, r3);
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("r1", "r2");
        assertThat(policy.admitNext(T0)).as("full, and a second copy does not fit").isEmpty();

        policy.finish(r1.id());
        var joined = policy.admitNext(T0).orElseThrow();
        assertThat(joined.created()).isFalse();
        assertThat(fixtures.names(joined)).containsExactly("r3");
    }

    @Test
    void aBlockedFirstRequestStopsEveryoneBehindItSoLeasesDrain() {
        var policy = new AdmissionPolicy(settings());
        var holder = fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        accept(policy, holder);
        policy.admitNext(T0).orElseThrow();
        var image = fixtures.request("image", Modality.IMAGE, WorkClass.INTERACTIVE, B, "image-x", MEDIUM, T0);
        var joiner = fixtures.text("joiner", WorkClass.CONTINUITY, C, "text-a", LARGE);
        accept(policy, image, joiner);

        // The image model needs 50 and only 40 is free. The joiner could share the resident text model,
        // but admitting it would keep that lease alive, so it waits behind the image request.
        assertThat(policy.admitNext(T0)).isEmpty();
        assertThat(policy.waiting()).containsExactly(image, joiner);

        policy.finish(holder.id());
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("image");
        assertThat(policy.waiting()).containsExactly(joiner);
    }

    @Test
    void aLargeRequestIsNotStarvedByAStreamOfSmallOnes() {
        var policy = new AdmissionPolicy(settings());
        var speech1 = fixtures.request("speech-1", Modality.SPEECH, WorkClass.CONTINUITY, A, "speech-1", MEDIUM, T0);
        accept(policy, speech1);
        policy.admitNext(T0).orElseThrow();
        var large = fixtures.request("large-text", Modality.TEXT, WorkClass.CONTINUITY, B, "text-big", new Demand(70, 10), T0);
        accept(policy, large);
        for (int index = 2; index <= 4; index++) {
            accept(policy, fixtures.request("speech-" + index, Modality.SPEECH, WorkClass.CONTINUITY, C, "speech-" + index,
                    SMALL, T0));
        }

        // 50 are leased, 70 are needed: the small speech requests would fit, but the large one is first
        // in order (text has never been served) and nothing may pass it.
        assertThat(policy.admitNext(T0)).isEmpty();
        policy.finish(speech1.id());
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("large-text");
    }

    @Test
    void modalitiesTakeTurnsSoAFloodOfTextCannotStarveSpeechOrImages() {
        var policy = new AdmissionPolicy(settings(BIG_BUDGET, 4));
        for (int index = 1; index <= 4; index++) {
            accept(policy, fixtures.request("text-" + index, Modality.TEXT, WorkClass.CONTINUITY, A, "text-" + index,
                    SMALL, T0));
        }
        accept(policy,
                fixtures.request("speech", Modality.SPEECH, WorkClass.CONTINUITY, B, "speech-s", SMALL, T0),
                fixtures.request("image", Modality.IMAGE, WorkClass.CONTINUITY, C, "image-x", SMALL, T0));

        assertThat(drain(policy)).containsExactly("text-1", "speech", "image", "text-2", "text-3", "text-4");
    }

    @Test
    void ownersTakeTurnsWithinAModality() {
        var policy = new AdmissionPolicy(settings(BIG_BUDGET, 4));
        for (int index = 1; index <= 3; index++) {
            accept(policy, fixtures.text("a-" + index, WorkClass.CONTINUITY, A, "model-a" + index, SMALL));
        }
        accept(policy, fixtures.text("b-1", WorkClass.CONTINUITY, B, "model-b1", SMALL));

        assertThat(drain(policy)).containsExactly("a-1", "b-1", "a-2", "a-3");
    }

    @Test
    void rotationNeverOutranksPriority() {
        var policy = new AdmissionPolicy(settings(BIG_BUDGET, 4));
        accept(policy, fixtures.text("first", WorkClass.CONTINUITY, A, "m1", SMALL));
        assertThat(drain(policy)).containsExactly("first");
        // Text was just served, so rotation favours the never-served image; the better class still wins.
        accept(policy,
                fixtures.request("prep-image", Modality.IMAGE, WorkClass.PREPARATION, B, "image-x", SMALL, T0),
                fixtures.text("second", WorkClass.CONTINUITY, A, "m2", SMALL));
        assertThat(drain(policy)).containsExactly("second", "prep-image");
    }

    @Test
    void backgroundOnlyLeasesAreTakenBackNewestFirstForABlockedInteractiveRequest() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.text("bg-text", WorkClass.CONTINUITY, A, "text-a", LARGE));
        var textLease = policy.admitNext(T0).orElseThrow().lease();
        accept(policy, fixtures.request("bg-speech", Modality.SPEECH, WorkClass.PREPARATION, A, "speech-s", SMALL, T0));
        var speechLease = policy.admitNext(T0).orElseThrow().lease();
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        accept(policy, turn);

        assertThat(policy.admitNext(T0)).as("only 30 are free").isEmpty();
        assertThat(policy.preemptionVictims(T0)).containsExactly(speechLease.id(), textLease.id());

        // Once the caller marks them, their capacity counts as coming free and nothing more is named.
        policy.markPreempting(speechLease.id());
        policy.markPreempting(textLease.id());
        assertThat(policy.preemptionVictims(T0)).isEmpty();
        assertThat(policy.admitNext(T0)).as("the model is still resident until revoked").isEmpty();

        assertThat(policy.revoke(speechLease.id()).orElseThrow().members()).hasSize(1);
        assertThat(policy.revoke(textLease.id())).isPresent();
        assertThat(policy.revoke(textLease.id())).as("a lease is revoked once").isEmpty();
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("turn");
    }

    @Test
    void workIsNeverInterruptedUnlessItMakesRoomForTheInteractiveRequest() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.request("interactive-image", Modality.IMAGE, WorkClass.INTERACTIVE, A, "image-x", MEDIUM, T0));
        policy.admitNext(T0).orElseThrow();
        accept(policy, fixtures.request("bg-speech", Modality.SPEECH, WorkClass.CONTINUITY, A, "speech-s", SMALL, T0));
        policy.admitNext(T0).orElseThrow();
        accept(policy, fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE));

        // 40 are free, the speech model gives 10 more, and the interactive image lease may not be taken.
        assertThat(policy.preemptionVictims(T0)).isEmpty();
    }

    @Test
    void aBackgroundRequestNeverTakesAnythingBack() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.text("bg-a", WorkClass.SPECULATIVE, A, "text-a", LARGE));
        policy.admitNext(T0).orElseThrow();
        accept(policy, fixtures.text("bg-b", WorkClass.CONTINUITY, B, "text-b", LARGE));

        assertThat(policy.preemptionVictims(T0)).isEmpty();
    }

    @Test
    void nothingIsTakenBackForARequestThatCanJoinAResidentLease() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.text("bg", WorkClass.CONTINUITY, A, "text-a", LARGE));
        policy.admitNext(T0).orElseThrow();
        accept(policy, fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-a", LARGE));

        assertThat(policy.preemptionVictims(T0)).isEmpty();
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("turn");
    }

    @Test
    void holdsReserveCapacityForUnconfirmedLeasesAndNeverMakeFreeNegative() {
        var policy = new AdmissionPolicy(settings());
        UUID earlier = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        policy.hold(earlier, new Demand(30, 30));
        assertThat(policy.free()).isEqualTo(new Demand(70, 70));
        policy.hold(other, new Demand(50, 50));
        assertThat(policy.free()).isEqualTo(new Demand(20, 20));
        accept(policy, fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        assertThat(policy.admitNext(T0)).as("the held capacity may still be in use").isEmpty();

        policy.hold(UUID.randomUUID(), new Demand(500, 500));
        assertThat(policy.free()).as("the budget was lowered below the holds").isEqualTo(Demand.NONE);
        assertThat(policy.held()).isEqualTo(new Demand(580, 580));

        assertThat(policy.unhold(earlier)).isTrue();
        assertThat(policy.unhold(earlier)).isFalse();
    }

    @Test
    void aConfirmedHoldMakesRoomForTheWaitingRequest() {
        var policy = new AdmissionPolicy(settings());
        UUID earlier = UUID.randomUUID();
        policy.hold(earlier, new Demand(80, 10));
        accept(policy, fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        assertThat(policy.admitNext(T0)).isEmpty();

        policy.unhold(earlier);
        assertThat(fixtures.names(policy.admitNext(T0).orElseThrow())).containsExactly("turn");
    }

    @Test
    void submissionRefusesDuplicatesAndRequestsThatCouldNeverFit() {
        var policy = new AdmissionPolicy(settings());
        var request = fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        assertThat(policy.submit(request)).isEqualTo(Submission.ACCEPTED);
        assertThat(policy.submit(request)).isEqualTo(Submission.DUPLICATE);
        policy.admitNext(T0).orElseThrow();
        assertThat(policy.submit(request)).as("also while it holds a lease").isEqualTo(Submission.DUPLICATE);
        assertThat(policy.submit(fixtures.text("huge", WorkClass.INTERACTIVE, A, "text-h", new Demand(101, 1))))
                .isEqualTo(Submission.EXCEEDS_CAPACITY);
        assertThat(policy.submit(fixtures.text("huge-ram", WorkClass.INTERACTIVE, A, "text-h", new Demand(1, 101))))
                .isEqualTo(Submission.EXCEEDS_CAPACITY);
        assertThat(policy.waiting()).isEmpty();
    }

    @Test
    void aWaitingRequestCanBeCancelledButALeaseHolderCannot() {
        var policy = new AdmissionPolicy(settings());
        var holder = fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        var waiter = fixtures.text("waiter", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        accept(policy, holder, waiter);
        policy.admitNext(T0).orElseThrow();

        assertThat(policy.cancel(holder.id())).isFalse();
        assertThat(policy.cancel(waiter.id())).isTrue();
        assertThat(policy.cancel(waiter.id())).isFalse();
        assertThat(policy.waiting()).isEmpty();
    }

    @Test
    void anUndoneGrantPutsItsRequestsBackInTheirOriginalOrder() {
        var policy = new AdmissionPolicy(settings());
        var r1 = fixtures.text("r1", WorkClass.CONTINUITY, A, "text-a", LARGE);
        var r2 = fixtures.text("r2", WorkClass.CONTINUITY, B, "text-a", LARGE);
        accept(policy, r1, r2);
        var grant = policy.admitNext(T0).orElseThrow();

        policy.undo(grant);

        assertThat(policy.leases()).isEmpty();
        assertThat(policy.free()).isEqualTo(CAPACITY);
        assertThat(policy.waiting()).containsExactly(r1, r2);
    }

    @Test
    void anUndoneJoinLeavesTheResidentLeaseAndItsOtherMembers() {
        var policy = new AdmissionPolicy(settings());
        var r1 = fixtures.text("r1", WorkClass.CONTINUITY, A, "text-a", LARGE);
        accept(policy, r1);
        var lease = policy.admitNext(T0).orElseThrow().lease();
        var r2 = fixtures.text("r2", WorkClass.CONTINUITY, B, "text-a", LARGE);
        accept(policy, r2);
        var join = policy.admitNext(T0).orElseThrow();

        policy.undo(join);

        assertThat(policy.leases()).extracting(Lease::members).containsExactly(List.of(r1.id()));
        assertThat(policy.leases().getFirst().id()).isEqualTo(lease.id());
        assertThat(policy.waiting()).containsExactly(r2);
    }

    @Test
    void anExpiredLeaseCannotBeJoinedAndItsCapacityBlocksUntilItIsRemoved() {
        var policy = new AdmissionPolicy(settings());
        accept(policy, fixtures.text("r1", WorkClass.CONTINUITY, A, "text-a", LARGE));
        policy.admitNext(T0).orElseThrow();
        accept(policy, fixtures.text("r2", WorkClass.CONTINUITY, B, "text-a", LARGE));

        assertThat(policy.admitNext(T0.plus(TTL))).as("not joinable once lapsed, and no room for a copy").isEmpty();
        assertThat(policy.expire(T0.plus(TTL))).hasSize(1);
        var fresh = policy.admitNext(T0.plus(TTL)).orElseThrow();
        assertThat(fresh.created()).isTrue();
        assertThat(fixtures.names(fresh)).containsExactly("r2");
    }

    @Test
    void settingsAndRequestsRejectValuesTheV29RecordCouldNotStore() {
        Map<Modality, Integer> batches = Map.of(Modality.TEXT, 1, Modality.IMAGE, 1, Modality.SPEECH, 1);
        assertThatThrownBy(() -> new AdmissionSettings(Demand.NONE, TTL, AGING, batches))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionSettings(CAPACITY, Duration.ZERO, AGING, batches))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionSettings(CAPACITY, TTL, Duration.ofSeconds(-1), batches))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionSettings(CAPACITY, TTL, AGING, Map.of(Modality.TEXT, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionSettings(CAPACITY, TTL, AGING,
                Map.of(Modality.TEXT, 65, Modality.IMAGE, 1, Modality.SPEECH, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Demand(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixtures.text("bad", WorkClass.INTERACTIVE, A, "not a model", LARGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixtures.text("free", WorkClass.INTERACTIVE, A, "text-a", Demand.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
