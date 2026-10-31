package org.rpgengine.inference.slots;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.SlotAssignments;
import org.rpgengine.inference.ConfiguredSlotAssignments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Owner settings of the four model slots (ADR 0021 decisions 2 and 8): saves assignments,
 * enforces the class-change acknowledgement, runs and records readiness probes, and reports the
 * status of every slot with its billing and privacy badge.
 *
 * <p>Rules this class keeps:
 * <ul>
 *   <li>An assignment whose class differs from the slot's current class is refused until the
 *       caller names the new class in {@code acknowledgeClass}; the acknowledgement is stored with
 *       its time. A slot that is not assigned counts as {@code local}, because nothing leaves the
 *       machine for it, so the first assignment to a class that does leave needs an acknowledgement.
 *       An environment assignment of an endpoint has no known class, so replacing it always does.</li>
 *   <li>A stored assignment overrides the environment assignment of the same text slot.</li>
 *   <li>A changed assignment loses its probe result: a probe describes one exact assignment.</li>
 *   <li>A changed assignment publishes {@link SlotAssignmentChanged} after it is saved; an identical
 *       save publishes nothing, so it costs no session its continuity.</li>
 *   <li>Settings are cached after the first read and replaced only after a successful save, so the
 *       dispatcher's hot path reads memory. A storage failure never produces a substitute assignment.</li>
 * </ul>
 * Thread-safe: writers are serialized; probes run outside the lock and are discarded when the
 * assignment changed while they ran.
 */
@Service
public class SlotSettingsService {
    private static final System.Logger LOG = System.getLogger(SlotSettingsService.class.getName());

    /** Where a slot's effective assignment comes from. */
    public enum Source {
        /** Saved through the settings API. */
        SETTINGS,
        /** Private startup settings ({@code engine.inference.slots.*}); read-only here. */
        ENVIRONMENT,
        /** Not assigned; tasks for it end {@code slot-unassigned}. */
        UNASSIGNED
    }

    /**
     * A requested assignment.
     *
     * @param slot slot to assign
     * @param backend protocol adapter name
     * @param model model identifier
     * @param effort reasoning effort; null for the image slot
     * @param declaredClass class declaration of an endpoint; null for other backends
     * @param fallbacks configured same-class fallbacks, in order
     * @param acknowledgeClass the class the owner acknowledges, or null
     */
    public record Request(ModelSlot slot, String backend, String model, String effort, BackendClass declaredClass,
            List<SlotSetting.Fallback> fallbacks, BackendClass acknowledgeClass) {
        /** Require the slot and the fallback list. */
        public Request {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(fallbacks, "fallbacks");
        }
    }

    /**
     * The visible state of one slot.
     *
     * @param slot the slot
     * @param source where the assignment comes from
     * @param backend backend, or null when unassigned
     * @param model model, or null when unassigned
     * @param effort effort, or null when unassigned
     * @param backendClass class of the assignment; null when unassigned or an environment endpoint
     *        whose class nobody declared
     * @param fallbacks configured fallbacks
     * @param acknowledgedClass last acknowledged class, or null
     * @param acknowledgedAt time of that acknowledgement, or null
     * @param probe last probe of this assignment; {@code NOT_RUN} unless the assignment is stored
     * @param updatedAt when the stored assignment was saved, or null
     */
    public record SlotStatus(ModelSlot slot, Source source, String backend, String model, String effort,
            BackendClass backendClass, List<SlotSetting.Fallback> fallbacks, BackendClass acknowledgedClass,
            Instant acknowledgedAt, ProbeResult probe, Instant updatedAt) {
    }

    /** The assignment would change the slot's class and the new class was not acknowledged. */
    public static final class AcknowledgementRequired extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient BackendClass from;
        private final transient BackendClass to;

        AcknowledgementRequired(BackendClass from, BackendClass to) {
            super("class change needs acknowledgement");
            this.from = from;
            this.to = to;
        }

        /** @return the current class, or null when it is unknown */
        public BackendClass from() {
            return from;
        }

        /** @return the class the request would move the slot to */
        public BackendClass to() {
            return to;
        }
    }

    /** A probe or selection named a slot that has no stored assignment. */
    public static final class NotAssigned extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotAssigned() {
            super("slot has no stored assignment");
        }
    }

    private final SlotSettingsStore store;
    private final BackendProbe probe;
    private final SlotAssignments configured;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Object lock = new Object();
    private volatile Map<ModelSlot, SlotSettingsStore.Stored> cache;

    /**
     * @param store durable storage
     * @param probe readiness probe port
     * @param configured the startup-settings assignments, shown for slots with no stored one
     * @param events receives {@link SlotAssignmentChanged}
     */
    @Autowired
    public SlotSettingsService(SlotSettingsStore store, BackendProbe probe, ConfiguredSlotAssignments configured,
            ApplicationEventPublisher events) {
        this(store, probe, (SlotAssignments) configured, Clock.systemUTC(), events);
    }

    SlotSettingsService(SlotSettingsStore store, BackendProbe probe, SlotAssignments configured, Clock clock) {
        this(store, probe, configured, clock, event -> { });
    }

    SlotSettingsService(SlotSettingsStore store, BackendProbe probe, SlotAssignments configured, Clock clock,
            ApplicationEventPublisher events) {
        this.store = Objects.requireNonNull(store, "store");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.configured = Objects.requireNonNull(configured, "configured");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Load the settings once the application is up, so the first task does not read storage. */
    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        try {
            stored();
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "slot settings not loaded at startup: " + failure.getClass().getName());
        }
    }

    /** Drop the cache so the next read loads storage again; for tests that rewrite the tables directly. */
    void forget() {
        synchronized (lock) {
            cache = null;
        }
    }

    /**
     * @param slot a slot
     * @return its stored assignment, or empty when none is stored
     * @throws RuntimeException when storage is unavailable or a stored row no longer validates; the
     *         caller must treat the slot as unreadable, not as unassigned
     */
    public Optional<SlotSetting> setting(ModelSlot slot) {
        return Optional.ofNullable(stored().get(slot)).map(SlotSettingsStore.Stored::setting);
    }

    /**
     * Validate and save an assignment, then announce it when it changed what the slot runs. A failure
     * while announcing is logged and never fails the save, which is already durable.
     *
     * @param request the requested assignment
     * @return the visible state of the slot after the save
     * @throws SlotSetting.Invalid for an assignment the rules refuse
     * @throws AcknowledgementRequired when the class changes and the new class is not acknowledged
     * @throws org.springframework.dao.DataAccessException when storage is unavailable; nothing changed
     */
    public SlotStatus update(Request request) {
        SlotSetting setting = SlotSetting.of(request.slot(), request.backend(), request.model(), request.effort(),
                request.declaredClass(), request.fallbacks());
        SlotStatus saved;
        boolean changed;
        synchronized (lock) {
            Map<ModelSlot, SlotSettingsStore.Stored> current = stored();
            SlotSettingsStore.Stored previous = current.get(setting.slot());
            BackendClass from = currentClass(setting.slot(), previous);
            BackendClass to = setting.backendClass();
            BackendClass acknowledged = previous == null ? null : previous.acknowledgedClass();
            Instant acknowledgedAt = previous == null ? null : previous.acknowledgedAt();
            Instant now = clock.instant();
            if (from != to) {
                if (request.acknowledgeClass() != to) {
                    throw new AcknowledgementRequired(from, to);
                }
                acknowledged = to;
                acknowledgedAt = now;
            }
            ProbeResult kept = previous != null && previous.setting().equals(setting) ? previous.probe()
                    : ProbeResult.notRun();
            SlotSettingsStore.Stored next = new SlotSettingsStore.Stored(setting, acknowledged, acknowledgedAt, kept, now);
            changed = !unchanged(setting, previous);
            store.save(next);
            Map<ModelSlot, SlotSettingsStore.Stored> copy = new EnumMap<>(ModelSlot.class);
            copy.putAll(current);
            copy.put(setting.slot(), next);
            cache = copy;
            saved = status(setting.slot(), copy);
        }
        if (changed) {
            // Outside the lock: retiring sessions calls the task port and writes the session journal.
            try {
                events.publishEvent(new SlotAssignmentChanged(setting.slot()));
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "slot change not announced: " + failure.getClass().getName());
            }
        }
        return saved;
    }

    /**
     * @return whether {@code setting} is what the slot already runs: the stored assignment, or the
     *         startup one when nothing is stored. A slot with neither has changed by being assigned.
     */
    private boolean unchanged(SlotSetting setting, SlotSettingsStore.Stored previous) {
        if (previous != null) {
            return previous.setting().equals(setting);
        }
        return environment(setting.slot()).filter(assignment -> assignment.equals(setting.toAssignment())).isPresent();
    }

    /**
     * Run the readiness probe of a stored assignment and record the result. The result is returned
     * and kept only while the assignment is unchanged; otherwise it is discarded and the current
     * state is returned.
     *
     * @param slot the slot to probe
     * @return the visible state of the slot
     * @throws NotAssigned when no assignment is stored for the slot
     * @throws org.springframework.dao.DataAccessException when storage is unavailable
     */
    public SlotStatus probe(ModelSlot slot) {
        SlotSettingsStore.Stored current = Optional.ofNullable(stored().get(slot)).orElseThrow(NotAssigned::new);
        long start = System.nanoTime();
        BackendProbe.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(probe.probe(current.setting().backend(), current.setting().primary()));
        } catch (RuntimeException failure) {
            // A probe never throws a runtime condition, so this is a defect; report it as a verdict.
            outcome = BackendProbe.Outcome.unreachable("probe-error");
        }
        long elapsed = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        ProbeResult result = new ProbeResult(outcome.state(), outcome.code(), clock.instant(), elapsed);
        synchronized (lock) {
            Map<ModelSlot, SlotSettingsStore.Stored> now = stored();
            SlotSettingsStore.Stored latest = now.get(slot);
            if (latest == null || !latest.setting().equals(current.setting())) {
                return status(slot, now);
            }
            if (store.saveProbe(slot, result)) {
                Map<ModelSlot, SlotSettingsStore.Stored> copy = new EnumMap<>(ModelSlot.class);
                copy.putAll(now);
                copy.put(slot, new SlotSettingsStore.Stored(latest.setting(), latest.acknowledgedClass(),
                        latest.acknowledgedAt(), result, latest.updatedAt()));
                cache = copy;
            }
            return status(slot, stored());
        }
    }

    /**
     * Choose the candidate that serves a slot now: the primary, or a configured fallback of the
     * same class (see {@link SlotFallback}). Probes candidates live and records nothing.
     *
     * @param slot the slot
     * @return the chosen candidate, or the explicit unavailable refusal; never a candidate of
     *         another class and never a default
     * @throws NotAssigned when no assignment is stored for the slot
     */
    public SlotFallback.Selection choose(ModelSlot slot) {
        SlotSetting setting = setting(slot).orElseThrow(NotAssigned::new);
        return SlotFallback.select(setting, candidate -> {
            try {
                return probe.probe(candidate.backend(), new EffectiveSettings(candidate.model(), candidate.effort()))
                        .state() == ProbeState.READY;
            } catch (RuntimeException failure) {
                return false;
            }
        });
    }

    /**
     * @return the visible state of the four slots, in the order light, standard, deliberate, image
     * @throws RuntimeException when storage is unavailable or unreadable
     */
    public List<SlotStatus> status() {
        Map<ModelSlot, SlotSettingsStore.Stored> all = stored();
        List<SlotStatus> result = new ArrayList<>();
        for (ModelSlot slot : ModelSlot.values()) {
            result.add(status(slot, all));
        }
        return List.copyOf(result);
    }

    private SlotStatus status(ModelSlot slot, Map<ModelSlot, SlotSettingsStore.Stored> all) {
        SlotSettingsStore.Stored row = all.get(slot);
        if (row != null) {
            SlotSetting setting = row.setting();
            return new SlotStatus(slot, Source.SETTINGS, setting.backend(), setting.model(), setting.effort(),
                    setting.backendClass(), setting.fallbacks(), row.acknowledgedClass(), row.acknowledgedAt(),
                    row.probe(), row.updatedAt());
        }
        Optional<SlotAssignment> env = environment(slot);
        if (env.isPresent()) {
            SlotAssignment assignment = env.get();
            List<SlotSetting.Fallback> alternatives = assignment.alternatives().stream()
                    .map(entry -> new SlotSetting.Fallback(assignment.backend(), entry.model(), entry.effort()))
                    .toList();
            return new SlotStatus(slot, Source.ENVIRONMENT, assignment.backend(), assignment.primary().model(),
                    assignment.primary().effort(), SlotBackends.fixedClass(assignment.backend()).orElse(null),
                    alternatives, null, null, ProbeResult.notRun(), null);
        }
        return new SlotStatus(slot, Source.UNASSIGNED, null, null, null, null, List.of(), null, null,
                ProbeResult.notRun(), null);
    }

    /** The class a slot has now: stored, else environment (null when unknown), else local. */
    private BackendClass currentClass(ModelSlot slot, SlotSettingsStore.Stored previous) {
        if (previous != null) {
            return previous.setting().backendClass();
        }
        Optional<SlotAssignment> env = environment(slot);
        if (env.isPresent()) {
            return SlotBackends.fixedClass(env.get().backend()).orElse(null);
        }
        return BackendClass.LOCAL;
    }

    private Optional<SlotAssignment> environment(ModelSlot slot) {
        Optional<InferenceSlot> text = slot.text();
        return text.isPresent() ? configured.resolve(text.get()) : Optional.empty();
    }

    private Map<ModelSlot, SlotSettingsStore.Stored> stored() {
        Map<ModelSlot, SlotSettingsStore.Stored> snapshot = cache;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (lock) {
            if (cache == null) {
                Map<ModelSlot, SlotSettingsStore.Stored> loaded = new EnumMap<>(ModelSlot.class);
                loaded.putAll(store.loadAll());
                cache = loaded;
            }
            return cache;
        }
    }
}
