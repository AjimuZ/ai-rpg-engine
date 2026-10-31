package org.rpgengine.inference.slots;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL {@link SlotSettingsStore} over {@code inference_slot_setting} and
 * {@code inference_slot_fallback} (V27). A save is one transaction, so a slot never exists with
 * half of its fallback list.
 */
@Component
public class JdbcSlotSettingsStore implements SlotSettingsStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * @param jdbc storage; a missing bean fails startup
     * @param manager transaction manager of the same data source
     */
    public JdbcSlotSettingsStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public Map<ModelSlot, Stored> loadAll() {
        return Objects.requireNonNull(transaction.execute(status -> {
            Map<ModelSlot, List<SlotSetting.Fallback>> fallbacks = new EnumMap<>(ModelSlot.class);
            jdbc.query("select slot, backend, model, effort from inference_slot_fallback order by slot, position",
                    (ResultSet row) -> {
                        ModelSlot slot = slot(row.getString("slot"));
                        fallbacks.computeIfAbsent(slot, key -> new ArrayList<>()).add(new SlotSetting.Fallback(
                                row.getString("backend"), row.getString("model"), row.getString("effort")));
                    });
            Map<ModelSlot, Stored> loaded = new EnumMap<>(ModelSlot.class);
            jdbc.query("select * from inference_slot_setting", (ResultSet row) -> {
                ModelSlot slot = slot(row.getString("slot"));
                loaded.put(slot, read(row, slot, fallbacks.getOrDefault(slot, List.of())));
            });
            return loaded;
        }));
    }

    @Override
    public void save(Stored stored) {
        SlotSetting setting = stored.setting();
        ProbeResult probe = stored.probe();
        transaction.executeWithoutResult(status -> {
            jdbc.update("insert into inference_slot_setting (slot, backend, model, effort, declared_class, "
                    + "backend_class, acknowledged_class, acknowledged_at, probe_state, probe_code, probe_at, "
                    + "probe_elapsed_ms, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "on conflict (slot) do update set backend = excluded.backend, model = excluded.model, "
                    + "effort = excluded.effort, declared_class = excluded.declared_class, "
                    + "backend_class = excluded.backend_class, acknowledged_class = excluded.acknowledged_class, "
                    + "acknowledged_at = excluded.acknowledged_at, probe_state = excluded.probe_state, "
                    + "probe_code = excluded.probe_code, probe_at = excluded.probe_at, "
                    + "probe_elapsed_ms = excluded.probe_elapsed_ms, updated_at = excluded.updated_at, "
                    + "row_version = inference_slot_setting.row_version + 1",
                    setting.slot().wire(), setting.backend(), setting.model(), setting.effort(),
                    setting.declaredClass() == null ? null : setting.declaredClass().wire(),
                    setting.backendClass().wire(),
                    stored.acknowledgedClass() == null ? null : stored.acknowledgedClass().wire(),
                    stamp(stored.acknowledgedAt()), probe.state().name(), probe.code(), stamp(probe.at()),
                    probe.state() == ProbeState.NOT_RUN ? null : probe.elapsedMillis(), stamp(stored.updatedAt()));
            jdbc.update("delete from inference_slot_fallback where slot = ?", setting.slot().wire());
            int position = 0;
            for (SlotSetting.Fallback entry : setting.fallbacks()) {
                jdbc.update("insert into inference_slot_fallback (slot, position, backend, model, effort) "
                        + "values (?, ?, ?, ?, ?)", setting.slot().wire(), position++, entry.backend(), entry.model(),
                        entry.effort());
            }
        });
    }

    @Override
    public boolean saveProbe(ModelSlot slot, ProbeResult probe) {
        return jdbc.update("update inference_slot_setting set probe_state = ?, probe_code = ?, probe_at = ?, "
                + "probe_elapsed_ms = ?, row_version = row_version + 1 where slot = ?", probe.state().name(),
                probe.code(), stamp(probe.at()), probe.state() == ProbeState.NOT_RUN ? null : probe.elapsedMillis(),
                slot.wire()) == 1;
    }

    private static Stored read(ResultSet row, ModelSlot slot, List<SlotSetting.Fallback> fallbacks)
            throws SQLException {
        try {
            String declared = row.getString("declared_class");
            String acknowledged = row.getString("acknowledged_class");
            Timestamp probeAt = row.getTimestamp("probe_at");
            Timestamp acknowledgedAt = row.getTimestamp("acknowledged_at");
            SlotSetting setting = new SlotSetting(slot, row.getString("backend"), row.getString("model"),
                    row.getString("effort"), declared == null ? null : BackendClass.parse(declared).orElseThrow(),
                    fallbacks);
            if (!setting.backendClass().wire().equals(row.getString("backend_class"))) {
                throw new IllegalStateException("stored class differs from the derived class");
            }
            ProbeState state = ProbeState.valueOf(row.getString("probe_state"));
            ProbeResult probe = state == ProbeState.NOT_RUN ? ProbeResult.notRun()
                    : new ProbeResult(state, row.getString("probe_code"), probeAt.toInstant(),
                            row.getLong("probe_elapsed_ms"));
            return new Stored(setting, acknowledged == null ? null : BackendClass.parse(acknowledged).orElseThrow(),
                    acknowledgedAt == null ? null : acknowledgedAt.toInstant(), probe,
                    row.getTimestamp("updated_at").toInstant());
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            // Never echo the row: it is private owner data.
            throw new IllegalStateException("a stored slot setting no longer validates", e);
        }
    }

    private static ModelSlot slot(String wire) {
        return ModelSlot.parse(wire).orElseThrow(() -> new IllegalStateException("unknown stored slot"));
    }

    private static Timestamp stamp(java.time.Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
