package org.rpgengine.inference.admission;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL {@link LeaseStore} over V29 {@code resource_lease}. Every method is one short READ
 * COMMITTED transaction with a five-second timeout; the V29 guard trigger repeats the lifecycle
 * rules, so a defect here fails loudly instead of corrupting the record. Times are bound as UTC
 * {@link OffsetDateTime} values so the session time zone cannot shift them.
 */
@Component
public class JdbcLeaseStore implements LeaseStore {
    private static final Set<LeaseStatus> SETTLEABLE = Set.of(LeaseStatus.RELEASED, LeaseStatus.EXPIRED, LeaseStatus.REVOKED);
    private static final String COLUMNS = "id, instance_id, modality, model, gpu_bytes, ram_bytes, batch_size, status, "
            + "granted_at, expires_at, finished_at";
    private static final RowMapper<LeaseRecord> RECORD = (row, index) -> new LeaseRecord(
            row.getObject("id", UUID.class), row.getObject("instance_id", UUID.class),
            Modality.valueOf(row.getString("modality")), row.getString("model"),
            new Demand(row.getLong("gpu_bytes"), row.getLong("ram_bytes")), row.getInt("batch_size"),
            LeaseStatus.valueOf(row.getString("status")), instant(row.getObject("granted_at", OffsetDateTime.class)),
            instant(row.getObject("expires_at", OffsetDateTime.class)),
            instant(row.getObject("finished_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * @param jdbc JDBC access joined to the bounded transactions
     * @param manager shared transaction manager
     */
    public JdbcLeaseStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /** {@inheritDoc} */
    @Override
    public void grant(UUID instance, Lease lease) {
        transaction.executeWithoutResult(status -> jdbc.update("insert into resource_lease (id, instance_id, modality, "
                        + "model, gpu_bytes, ram_bytes, batch_size, status, granted_at, expires_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                lease.id(), instance, lease.modality().name(), lease.model(), lease.demand().gpuBytes(),
                lease.demand().ramBytes(), lease.members().size(), time(lease.grantedAt()), time(lease.expiresAt())));
    }

    /** {@inheritDoc} */
    @Override
    public boolean resize(UUID leaseId, UUID instance, int size) {
        if (size < 1 || size > AdmissionSettings.MAX_BATCH_BOUND) {
            return false;
        }
        return Objects.equals(transaction.execute(status -> jdbc.update("update resource_lease set batch_size = ?, "
                + "row_version = row_version + 1 where id = ? and instance_id = ? and status = 'ACTIVE'",
                size, leaseId, instance)), 1);
    }

    /** {@inheritDoc} */
    @Override
    public boolean renew(UUID leaseId, UUID instance, Instant expiresAt, Instant now) {
        return Objects.equals(transaction.execute(status -> jdbc.update("update resource_lease set "
                + "expires_at = greatest(expires_at, ?), row_version = row_version + 1 "
                + "where id = ? and instance_id = ? and status = 'ACTIVE' and expires_at > ?",
                time(expiresAt), leaseId, instance, time(now))), 1);
    }

    /** {@inheritDoc} */
    @Override
    public boolean settle(UUID leaseId, UUID instance, LeaseStatus terminal, Instant now) {
        if (!SETTLEABLE.contains(terminal)) {
            throw new IllegalArgumentException("unsupported settlement");
        }
        return Objects.equals(transaction.execute(status -> jdbc.update("update resource_lease set status = ?, "
                + "finished_at = ?, row_version = row_version + 1 "
                + "where id = ? and instance_id = ? and status = 'ACTIVE'", terminal.name(), time(now), leaseId,
                instance)), 1);
    }

    /** {@inheritDoc} */
    @Override
    public List<LeaseRecord> recover(UUID instance, Instant now) {
        return transaction.execute(status -> jdbc.query("update resource_lease set status = 'RECONCILING', "
                + "instance_id = ?, row_version = row_version + 1 where status in ('ACTIVE', 'RECONCILING') "
                + "and instance_id <> ? returning " + COLUMNS, RECORD, instance, instance).stream()
                .sorted(Comparator.comparing(LeaseRecord::grantedAt)).toList());
    }

    /** {@inheritDoc} */
    @Override
    public boolean confirmUnloaded(UUID leaseId, UUID instance, Instant now) {
        return Objects.equals(transaction.execute(status -> jdbc.update("update resource_lease set status = 'RELEASED', "
                + "finished_at = ?, row_version = row_version + 1 "
                + "where id = ? and instance_id = ? and status = 'RECONCILING'", time(now), leaseId, instance)), 1);
    }

    /** {@inheritDoc} */
    @Override
    public List<LeaseRecord> open() {
        return jdbc.query("select " + COLUMNS + " from resource_lease where status in ('ACTIVE', 'RECONCILING') "
                + "order by granted_at, id", RECORD);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LeaseStatus> status(UUID leaseId) {
        return jdbc.queryForList("select status from resource_lease where id = ?", String.class, leaseId).stream()
                .findFirst().map(LeaseStatus::valueOf);
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
