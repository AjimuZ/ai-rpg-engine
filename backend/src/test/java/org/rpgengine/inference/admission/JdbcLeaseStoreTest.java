package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.B;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.MEDIUM;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.TTL;
import static org.rpgengine.inference.admission.AdmissionFixtures.settings;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.scheduling.WorkClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V29 {@code resource_lease} on real PostgreSQL: the store's round trip and fencing, takeover of an
 * earlier process's leases, the table's own lifecycle guard, and the coordinator running over the
 * store across a simulated restart.
 */
@SpringBootTest
class JdbcLeaseStoreTest {
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000e2");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcLeaseStore store;

    /** Construct the injected fixture; missing PostgreSQL fails application startup. */
    JdbcLeaseStoreTest() {
    }

    /** Empty the table (TRUNCATE is not row-level, so the no-delete guard allows it) around each test. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.execute("truncate resource_lease");
    }

    private static Lease lease(String model, Demand demand, int members) {
        List<UUID> ids = IntStream.range(0, members).mapToObj(index -> UUID.randomUUID()).toList();
        return new Lease(UUID.randomUUID(), Modality.TEXT, model, demand, ids, T0, T0.plus(TTL));
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private int insert(UUID id, String modality, String model, long gpu, long ram, int batch, String status,
            Instant expires, Instant finished) {
        return jdbc.update("insert into resource_lease (id, instance_id, modality, model, gpu_bytes, ram_bytes, "
                + "batch_size, status, granted_at, expires_at, finished_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, INSTANCE, modality, model, gpu, ram, batch, status, time(T0), time(expires),
                finished == null ? null : time(finished));
    }

    @Test
    void aLeaseRoundTripsThroughGrantResizeRenewAndRelease() {
        Lease lease = lease("text-a", LARGE, 2);
        store.grant(INSTANCE, lease);

        LeaseRecord row = store.open().getFirst();
        assertThat(row).isEqualTo(new LeaseRecord(lease.id(), INSTANCE, Modality.TEXT, "text-a", LARGE, 2,
                LeaseStatus.ACTIVE, T0, T0.plus(TTL), null));

        assertThat(store.resize(lease.id(), INSTANCE, 3)).isTrue();
        assertThat(store.resize(lease.id(), INSTANCE, 0)).as("an active lease keeps a member").isFalse();
        assertThat(store.resize(lease.id(), INSTANCE, 65)).isFalse();
        assertThat(store.renew(lease.id(), INSTANCE, T0.plusSeconds(100), T0.plusSeconds(40))).isTrue();
        assertThat(store.renew(lease.id(), INSTANCE, T0.plusSeconds(70), T0.plusSeconds(41))).as("never shortens").isTrue();
        row = store.open().getFirst();
        assertThat(row.batchSize()).isEqualTo(3);
        assertThat(row.expiresAt()).isEqualTo(T0.plusSeconds(100));

        assertThat(store.settle(lease.id(), INSTANCE, LeaseStatus.RELEASED, T0.plusSeconds(50))).isTrue();
        assertThat(store.settle(lease.id(), INSTANCE, LeaseStatus.EXPIRED, T0.plusSeconds(51))).as("final").isFalse();
        assertThat(store.resize(lease.id(), INSTANCE, 1)).isFalse();
        assertThat(store.renew(lease.id(), INSTANCE, T0.plusSeconds(200), T0.plusSeconds(52))).isFalse();
        assertThat(store.open()).isEmpty();
        assertThat(store.status(lease.id())).contains(LeaseStatus.RELEASED);
        assertThat(store.status(UUID.randomUUID())).isEmpty();
        assertThat(jdbc.queryForObject("select finished_at from resource_lease where id = ?", OffsetDateTime.class,
                lease.id()).toInstant()).isEqualTo(T0.plusSeconds(50));
    }

    @Test
    void anExpiredLeaseCannotBeRenewedAndOnlyTerminalStatusesSettle() {
        Lease lease = lease("text-a", LARGE, 1);
        store.grant(INSTANCE, lease);

        assertThat(store.renew(lease.id(), INSTANCE, T0.plusSeconds(200), T0.plus(TTL))).isFalse();
        assertThatThrownBy(() -> store.settle(lease.id(), INSTANCE, LeaseStatus.ACTIVE, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.settle(lease.id(), INSTANCE, LeaseStatus.RECONCILING, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.settle(lease.id(), INSTANCE, LeaseStatus.EXPIRED, T0.plus(TTL))).isTrue();
        assertThat(store.status(lease.id())).contains(LeaseStatus.EXPIRED);
    }

    @Test
    void onlyTheOwningProcessChangesALease() {
        Lease lease = lease("text-a", LARGE, 1);
        store.grant(INSTANCE, lease);

        assertThat(store.resize(lease.id(), OTHER, 2)).isFalse();
        assertThat(store.renew(lease.id(), OTHER, T0.plusSeconds(200), T0)).isFalse();
        assertThat(store.settle(lease.id(), OTHER, LeaseStatus.REVOKED, T0)).isFalse();
        assertThat(store.confirmUnloaded(lease.id(), OTHER, T0)).isFalse();
        assertThat(store.confirmUnloaded(lease.id(), INSTANCE, T0)).as("only a reconciling lease").isFalse();
        assertThat(store.open().getFirst().instance()).isEqualTo(INSTANCE);
        assertThat(store.open().getFirst().status()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    void aNewProcessTakesOverOpenLeasesOfEarlierOnesAndKeepsThemReconcilingUntilConfirmed() {
        Lease older = new Lease(UUID.randomUUID(), Modality.IMAGE, "image-x", MEDIUM, List.of(UUID.randomUUID()), T0,
                T0.plus(TTL));
        Lease newer = new Lease(UUID.randomUUID(), Modality.SPEECH, "speech-s", LARGE, List.of(UUID.randomUUID()),
                T0.plusSeconds(5), T0.plusSeconds(65));
        Lease finished = lease("text-done", LARGE, 1);
        store.grant(OTHER, newer);
        store.grant(OTHER, older);
        store.grant(OTHER, finished);
        store.settle(finished.id(), OTHER, LeaseStatus.RELEASED, T0);

        List<LeaseRecord> taken = store.recover(INSTANCE, T0.plusSeconds(10));

        assertThat(taken).extracting(LeaseRecord::id).containsExactly(older.id(), newer.id());
        assertThat(taken).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(LeaseStatus.RECONCILING);
            assertThat(row.instance()).isEqualTo(INSTANCE);
            assertThat(row.finishedAt()).isNull();
        });
        assertThat(taken.getFirst().demand()).isEqualTo(MEDIUM);
        assertThat(store.recover(INSTANCE, T0.plusSeconds(11))).as("taken over once").isEmpty();
        assertThat(store.status(finished.id())).contains(LeaseStatus.RELEASED);
        // A third process takes the hold over again, because it cannot know the models are gone either.
        assertThat(store.recover(UUID.randomUUID(), T0.plusSeconds(12))).hasSize(2);
        assertThat(store.confirmUnloaded(older.id(), INSTANCE, T0.plusSeconds(13))).as("no longer the owner").isFalse();
    }

    @Test
    void aReconcilingLeaseIsReleasedOnlyByItsOwnerAndThenStaysReleased() {
        Lease lease = lease("text-a", LARGE, 1);
        store.grant(OTHER, lease);
        store.recover(INSTANCE, T0);

        assertThat(store.confirmUnloaded(lease.id(), INSTANCE, T0.plusSeconds(20))).isTrue();
        assertThat(store.confirmUnloaded(lease.id(), INSTANCE, T0.plusSeconds(21))).isFalse();
        assertThat(store.status(lease.id())).contains(LeaseStatus.RELEASED);
        assertThat(store.open()).isEmpty();
    }

    @Test
    void theTableRefusesRowsAndChangesThatBreakTheLeaseLifecycle() {
        UUID id = UUID.randomUUID();
        Instant expires = T0.plus(TTL);
        assertThat(insert(id, "TEXT", "text-a", 60, 10, 1, "ACTIVE", expires, null)).isEqualTo(1);

        String[] refused = {"unknown modality", "no capacity", "active without members", "unsafe model",
                "expiry before grant", "active but finished", "terminal without finish time", "too large a batch"};
        List<Runnable> inserts = List.of(
                () -> insert(UUID.randomUUID(), "VIDEO", "text-a", 60, 10, 1, "ACTIVE", expires, null),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 0, 0, 1, "ACTIVE", expires, null),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 60, 10, 0, "ACTIVE", expires, null),
                () -> insert(UUID.randomUUID(), "TEXT", "bad model", 60, 10, 1, "ACTIVE", expires, null),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 60, 10, 1, "ACTIVE", T0, null),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 60, 10, 1, "ACTIVE", expires, T0),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 60, 10, 1, "RELEASED", expires, null),
                () -> insert(UUID.randomUUID(), "TEXT", "text-a", 60, 10, 65, "ACTIVE", expires, null));
        for (int index = 0; index < inserts.size(); index++) {
            assertThatThrownBy(inserts.get(index)::run).as(refused[index]).isInstanceOf(DataAccessException.class);
        }

        String step = ", row_version = row_version + 1 where id = ?";
        assertThatThrownBy(() -> jdbc.update("update resource_lease set gpu_bytes = 1" + step, id))
                .as("the reserved capacity is fixed").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set model = 'text-b'" + step, id))
                .as("the model is fixed").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set instance_id = ?" + step, OTHER, id))
                .as("only a takeover changes the owner").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set expires_at = ?" + step, time(T0.plusSeconds(30)), id))
                .as("a renewal never shortens a lease").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set status = 'RELEASED'" + step, id))
                .as("an end needs its finish time").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set batch_size = 2 where id = ?", id))
                .as("every change steps the row version").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from resource_lease where id = ?", id))
                .as("rows are never deleted").isInstanceOf(DataAccessException.class);

        assertThat(jdbc.update("update resource_lease set status = 'RELEASED', finished_at = ?" + step, time(T0), id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set status = 'ACTIVE', finished_at = null" + step, id))
                .as("an ended lease is final").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set batch_size = 1" + step, id))
                .as("an ended lease does not change").isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from resource_lease", Integer.class)).isEqualTo(1);
    }

    @Test
    void aReconcilingLeaseCannotReturnToActive() {
        UUID id = UUID.randomUUID();
        insert(id, "TEXT", "text-a", 60, 10, 1, "ACTIVE", T0.plus(TTL), null);
        assertThat(jdbc.update("update resource_lease set status = 'RECONCILING', row_version = row_version + 1 "
                + "where id = ?", id)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("update resource_lease set status = 'ACTIVE', row_version = row_version + 1 "
                + "where id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update resource_lease set status = 'EXPIRED', finished_at = ?, "
                + "row_version = row_version + 1 where id = ?", time(T0), id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void theCoordinatorSurvivesARestartOverRealStorage() {
        var clock = new SteppedClock(T0);
        var first = new AdmissionCoordinator(settings(), store, clock);
        var fixtures = new AdmissionFixtures();
        var holder = fixtures.text("holder", WorkClass.CONTINUITY, A, "text-a", LARGE);
        first.submit(holder);
        var leaseId = first.poll().grants().getFirst().lease().id();
        var sharer = fixtures.text("sharer", WorkClass.CONTINUITY, B, "text-a", LARGE);
        first.submit(sharer);
        first.poll();
        assertThat(store.open()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(leaseId);
            assertThat(row.batchSize()).isEqualTo(2);
            assertThat(row.instance()).isEqualTo(first.instance());
        });
        clock.advance(Duration.ofSeconds(20));
        assertThat(first.renew(leaseId)).isTrue();
        assertThat(store.open().getFirst().expiresAt()).isEqualTo(T0.plusSeconds(80));

        // The process dies without releasing anything; the next one finds the lease and keeps its capacity.
        var second = new AdmissionCoordinator(settings(), store, clock);
        assertThat(second.recover()).extracting(LeaseRecord::id).containsExactly(leaseId);
        assertThat(store.status(leaseId)).contains(LeaseStatus.RECONCILING);
        assertThat(second.free()).isEqualTo(new Demand(CAPACITY.gpuBytes() - LARGE.gpuBytes(),
                CAPACITY.ramBytes() - LARGE.ramBytes()));
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, A, "text-b", LARGE);
        second.submit(turn);
        assertThat(second.poll().grants()).isEmpty();
        assertThat(first.finish(holder.id())).as("the earlier process is fenced").isTrue();
        assertThat(store.status(leaseId)).contains(LeaseStatus.RECONCILING);

        assertThat(second.confirmUnloaded(leaseId)).isTrue();
        var granted = second.poll().grants().getFirst();
        assertThat(granted.admitted()).containsExactly(turn);
        assertThat(store.status(granted.lease().id())).contains(LeaseStatus.ACTIVE);

        clock.advance(Duration.ofSeconds(60));
        assertThat(second.poll().expired()).hasSize(1);
        assertThat(store.status(granted.lease().id())).contains(LeaseStatus.EXPIRED);
        assertThat(store.open()).isEmpty();
    }
}
