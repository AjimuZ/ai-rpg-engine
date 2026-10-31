package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.B;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.SMALL;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.settings;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.scheduling.WorkClass;

/**
 * The coordinator over an in-memory store and a stepped clock: grants are recorded before they are
 * returned, leases expire and renew against the clock, a refused write takes a grant back, a restart
 * keeps the capacity of the leases it finds, and a process cannot change a lease it no longer owns.
 */
class AdmissionCoordinatorTest {
    private final AdmissionFixtures fixtures = new AdmissionFixtures();
    private final MemoryLeaseStore store = new MemoryLeaseStore();
    private final SteppedClock clock = new SteppedClock(T0);
    private final AdmissionCoordinator coordinator = new AdmissionCoordinator(settings(), store, clock);

    @Test
    void aPolledGrantIsRecordedAsAnActiveLeaseBeforeItIsReturned() {
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        assertThat(coordinator.submit(turn)).isEqualTo(Submission.ACCEPTED);

        var poll = coordinator.poll();

        assertThat(poll.expired()).isEmpty();
        assertThat(poll.grants()).hasSize(1);
        var lease = poll.grants().getFirst().lease();
        var row = store.rows.get(lease.id());
        assertThat(row.status()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(row.instance()).isEqualTo(coordinator.instance());
        assertThat(row.demand()).isEqualTo(LARGE);
        assertThat(row.batchSize()).isEqualTo(1);
        assertThat(row.expiresAt()).isEqualTo(lease.expiresAt());
        assertThat(coordinator.poll().grants()).isEmpty();
    }

    @Test
    void joiningAndLeavingKeepTheStoredBatchSizeAndTheLastMemberReleasesTheLease() {
        var first = fixtures.text("first", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        coordinator.submit(first);
        var leaseId = coordinator.poll().grants().getFirst().lease().id();
        var second = fixtures.text("second", WorkClass.CONTINUITY, B, "text-a", LARGE);
        coordinator.submit(second);

        var joined = coordinator.poll().grants().getFirst();
        assertThat(joined.created()).isFalse();
        assertThat(store.rows.get(leaseId).batchSize()).isEqualTo(2);

        assertThat(coordinator.finish(first.id())).isTrue();
        assertThat(store.rows.get(leaseId).batchSize()).isEqualTo(1);
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(coordinator.finish(second.id())).isTrue();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(store.rows.get(leaseId).finishedAt()).isEqualTo(T0);
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
        assertThat(coordinator.finish(second.id())).isFalse();
    }

    @Test
    void aLeaseNobodyRenewsExpiresAndItsCapacityGoesToTheWaitingRequestInTheSamePoll() {
        coordinator.submit(fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var leaseId = coordinator.poll().grants().getFirst().lease().id();
        var next = fixtures.text("next", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        coordinator.submit(next);
        clock.advance(Duration.ofSeconds(59));
        assertThat(coordinator.poll().grants()).as("still leased at 59 s").isEmpty();

        clock.advance(Duration.ofSeconds(1));
        var poll = coordinator.poll();

        assertThat(poll.expired()).extracting(Lease::id).containsExactly(leaseId);
        assertThat(poll.unrecorded()).isZero();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(store.rows.get(leaseId).finishedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(poll.grants()).hasSize(1);
        assertThat(poll.grants().getFirst().admitted()).containsExactly(next);
    }

    @Test
    void renewingKeepsALeaseAliveUntilTheHolderStopsAndNeverRevivesAnExpiredOne() {
        coordinator.submit(fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var leaseId = coordinator.poll().grants().getFirst().lease().id();

        clock.advance(Duration.ofSeconds(40));
        assertThat(coordinator.renew(leaseId)).isTrue();
        assertThat(store.rows.get(leaseId).expiresAt()).isEqualTo(T0.plusSeconds(100));
        clock.advance(Duration.ofSeconds(50));
        assertThat(coordinator.poll().expired()).as("alive at 90 s").isEmpty();

        clock.advance(Duration.ofSeconds(10));
        assertThat(coordinator.poll().expired()).hasSize(1);
        assertThat(coordinator.renew(leaseId)).isFalse();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.EXPIRED);
    }

    @Test
    void anExpiryThatCannotBeRecordedIsReportedAndLaterTurnedIntoAHoldByTheNextProcess() {
        coordinator.submit(fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var leaseId = coordinator.poll().grants().getFirst().lease().id();
        store.failSettle = true;
        clock.advance(Duration.ofSeconds(60));

        var poll = coordinator.poll();

        assertThat(poll.expired()).hasSize(1);
        assertThat(poll.unrecorded()).isEqualTo(1);
        assertThat(coordinator.free()).as("the lease is over in accounting").isEqualTo(CAPACITY);
        store.failSettle = false;
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.ACTIVE);
        var restarted = new AdmissionCoordinator(settings(), store, clock);
        assertThat(restarted.recover()).extracting(LeaseRecord::id).containsExactly(leaseId);
        assertThat(restarted.free()).as("capacity is overstated, not lost").isEqualTo(new Demand(40, 90));
    }

    @Test
    void aRefusedWriteTakesTheGrantBackAndTheNextPollRetries() {
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        coordinator.submit(turn);
        store.grantsBeforeFailure = 0;

        assertThatThrownBy(coordinator::poll).isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.waiting()).containsExactly(turn);
        assertThat(coordinator.leases()).isEmpty();
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
        assertThat(store.rows).isEmpty();

        store.grantsBeforeFailure = -1;
        assertThat(coordinator.poll().grants()).hasSize(1);
    }

    @Test
    void aRecordedGrantIsNeverLostWhenALaterGrantInTheSamePollIsRefused() {
        var coordinator = new AdmissionCoordinator(settings(new Demand(1000, 1000), 4), store, clock);
        var first = fixtures.text("first", WorkClass.INTERACTIVE, A, "m1", SMALL);
        var second = fixtures.text("second", WorkClass.INTERACTIVE, B, "m2", SMALL);
        coordinator.submit(first);
        coordinator.submit(second);
        store.grantsBeforeFailure = 1;

        var poll = coordinator.poll();

        assertThat(poll.grants()).extracting(grant -> grant.admitted().getFirst()).containsExactly(first);
        assertThat(coordinator.waiting()).containsExactly(second);
        store.grantsBeforeFailure = -1;
        assertThat(coordinator.poll().grants()).extracting(grant -> grant.admitted().getFirst()).containsExactly(second);
    }

    @Test
    void anInteractiveRequestTakesBackABackgroundLeaseOnlyAfterItIsRevoked() {
        coordinator.submit(fixtures.text("plan", WorkClass.CONTINUITY, A, "text-a", LARGE));
        var leaseId = coordinator.poll().grants().getFirst().lease().id();
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        coordinator.submit(turn);
        assertThat(coordinator.poll().grants()).isEmpty();

        assertThat(coordinator.preemptionVictims()).containsExactly(leaseId);
        assertThat(coordinator.preemptionVictims()).as("named once").isEmpty();
        assertThat(coordinator.poll().grants()).as("the model is still resident").isEmpty();

        var revoked = coordinator.revoke(leaseId).orElseThrow();
        assertThat(revoked.members()).hasSize(1);
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.REVOKED);
        assertThat(coordinator.revoke(leaseId)).isEmpty();
        assertThat(coordinator.poll().grants().getFirst().admitted()).containsExactly(turn);
    }

    @Test
    void aRestartKeepsTheCapacityOfTheLeasesItFindsUntilTheyAreConfirmedUnloaded() {
        var holder = fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        coordinator.submit(holder);
        var leaseId = coordinator.poll().grants().getFirst().lease().id();

        var restarted = new AdmissionCoordinator(settings(), store, clock);
        assertThat(restarted.instance()).isNotEqualTo(coordinator.instance());
        List<LeaseRecord> taken = restarted.recover();

        assertThat(taken).hasSize(1);
        assertThat(taken.getFirst().status()).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(store.rows.get(leaseId).instance()).isEqualTo(restarted.instance());
        assertThat(restarted.held()).isEqualTo(LARGE);
        assertThat(restarted.recover()).as("taken over once").isEmpty();
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        restarted.submit(turn);
        assertThat(restarted.poll().grants()).as("the old model may still be resident").isEmpty();

        // The earlier process cannot change a lease it no longer owns.
        assertThat(coordinator.finish(holder.id())).isTrue();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(coordinator.renew(leaseId)).isFalse();

        assertThat(restarted.confirmUnloaded(leaseId)).isTrue();
        assertThat(restarted.confirmUnloaded(leaseId)).isFalse();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(restarted.held()).isEqualTo(Demand.NONE);
        assertThat(restarted.poll().grants().getFirst().admitted()).containsExactly(turn);
    }

    @Test
    void underTheHoldingRuleAnExpiredLeaseKeepsItsCapacityUntilItsModelIsConfirmedGone() {
        var holding = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        holding.submit(fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var leaseId = holding.poll().grants().getFirst().lease().id();
        var next = fixtures.text("next", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        holding.submit(next);
        clock.advance(Duration.ofSeconds(60));

        var poll = holding.poll();

        assertThat(poll.expired()).extracting(Lease::id).containsExactly(leaseId);
        assertThat(poll.unrecorded()).isZero();
        assertThat(poll.grants()).as("the old model may still be resident").isEmpty();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(store.rows.get(leaseId).instance()).isEqualTo(holding.instance());
        assertThat(holding.held()).isEqualTo(LARGE);

        assertThat(holding.confirmUnloaded(leaseId)).isTrue();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(holding.poll().grants().getFirst().admitted()).containsExactly(next);
    }

    @Test
    void underTheHoldingRuleAnExpiryTheStoreRefusedIsWrittenWhenTheModelIsConfirmedGone() {
        var holding = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        holding.submit(fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE));
        var leaseId = holding.poll().grants().getFirst().lease().id();
        store.failSettle = true;
        clock.advance(Duration.ofSeconds(60));

        assertThat(holding.poll().unrecorded()).isEqualTo(1);
        assertThat(holding.held()).as("the hold does not depend on the write").isEqualTo(LARGE);
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.ACTIVE);

        store.failSettle = false;
        assertThat(holding.confirmUnloaded(leaseId)).isTrue();
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(holding.held()).isEqualTo(Demand.NONE);
    }

    @Test
    void reconcilingALiveLeaseEndsItsAdmissionAndKeepsItsCapacityHeld() {
        var holder = fixtures.text("holder", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        coordinator.submit(holder);
        var leaseId = coordinator.poll().grants().getFirst().lease().id();

        assertThat(coordinator.reconcile(leaseId).orElseThrow().members()).containsExactly(holder.id());

        assertThat(coordinator.leases()).isEmpty();
        assertThat(coordinator.finish(holder.id())).isFalse();
        assertThat(coordinator.held()).isEqualTo(LARGE);
        assertThat(store.rows.get(leaseId).status()).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(coordinator.reconcile(leaseId)).as("not live any more").isEmpty();
        assertThat(coordinator.confirmUnloaded(leaseId)).isTrue();
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
    }

    @Test
    void onlyAWaitingInteractiveTextRequestCountsAsInteractiveTextDemand() {
        assertThat(coordinator.interactiveTextWaiting()).isFalse();
        coordinator.submit(fixtures.text("plan", WorkClass.CONTINUITY, A, "text-a", SMALL));
        coordinator.submit(fixtures.request("art", Modality.IMAGE, WorkClass.INTERACTIVE, A, "image-a", SMALL, T0));
        assertThat(coordinator.interactiveTextWaiting()).isFalse();

        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", SMALL);
        coordinator.submit(turn);
        assertThat(coordinator.interactiveTextWaiting()).isTrue();
        coordinator.cancel(turn.id());
        assertThat(coordinator.interactiveTextWaiting()).isFalse();
    }

    @Test
    void duplicatesAndOversizedRequestsAreRefusedAndCancellationRemovesAWaitingRequest() {
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-a", LARGE);
        assertThat(coordinator.submit(turn)).isEqualTo(Submission.ACCEPTED);
        assertThat(coordinator.submit(turn)).isEqualTo(Submission.DUPLICATE);
        assertThat(coordinator.submit(fixtures.text("huge", WorkClass.INTERACTIVE, A, "text-h", new Demand(1000, 1))))
                .isEqualTo(Submission.EXCEEDS_CAPACITY);
        assertThat(coordinator.cancel(turn.id())).isTrue();
        assertThat(coordinator.poll().grants()).isEmpty();
        assertThatThrownBy(() -> coordinator.submit(null)).isInstanceOf(NullPointerException.class);
    }
}
