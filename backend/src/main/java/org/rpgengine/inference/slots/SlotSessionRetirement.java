package org.rpgengine.inference.slots;

import java.util.Objects;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.session.ScopedSessions;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Retires the sessions bound to a slot whose assignment changed, so its next task opens a fresh
 * conversation while other slots keep theirs (P21.3-B, docs/34 section 12). The settings service
 * publishes an event instead of calling this directly, because the registry depends on the task
 * port, which reads the settings through the profile resolver.
 */
@Component
public class SlotSessionRetirement {
    private final ScopedSessions sessions;
    private final ProfileResolver profiles;

    /**
     * @param sessions the session registry
     * @param profiles names the task slots an assignment serves, which {@code fixedQuality} changes
     */
    public SlotSessionRetirement(ScopedSessions sessions, ProfileResolver profiles) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Retire what the change affects. The image slot has no text session, so its changes retire nothing.
     *
     * @param change the changed slot
     */
    @EventListener
    public void onChange(SlotAssignmentChanged change) {
        change.slot().text().ifPresent(assigned -> profiles.slotsServedBy(assigned).forEach(sessions::retireSlot));
    }
}
