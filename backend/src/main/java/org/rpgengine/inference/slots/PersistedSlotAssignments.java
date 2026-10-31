package org.rpgengine.inference.slots;

import java.util.Objects;
import java.util.Optional;
import org.rpgengine.inference.ConfiguredSlotAssignments;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.SlotAssignments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link SlotAssignments} the profile resolver reads: a text slot saved through the settings
 * API wins, otherwise the startup settings of {@link ConfiguredSlotAssignments} apply. Reads come
 * from the settings cache, so changes made through the API take effect on the next task.
 *
 * <p>When the stored settings cannot be read, the slot resolves as unassigned and the task ends
 * {@code slot-unassigned}. Falling back to the startup settings there would silently run a
 * different backend, and possibly a different billing and privacy class, than the owner saved.
 */
@Component
@Primary
public class PersistedSlotAssignments implements SlotAssignments {
    private static final System.Logger LOG = System.getLogger(PersistedSlotAssignments.class.getName());
    private final SlotSettingsService settings;
    private final SlotAssignments configured;

    /**
     * @param settings stored owner settings
     * @param configured startup settings used for slots with no stored assignment
     */
    @Autowired
    public PersistedSlotAssignments(SlotSettingsService settings, ConfiguredSlotAssignments configured) {
        this(settings, (SlotAssignments) configured);
    }

    PersistedSlotAssignments(SlotSettingsService settings, SlotAssignments configured) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    @Override
    public Optional<SlotAssignment> resolve(InferenceSlot slot) {
        try {
            Optional<SlotSetting> stored = settings.setting(ModelSlot.of(slot));
            if (stored.isPresent()) {
                return Optional.of(stored.get().toAssignment());
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "slot settings unreadable: " + failure.getClass().getName());
            return Optional.empty();
        }
        return configured.resolve(slot);
    }
}
