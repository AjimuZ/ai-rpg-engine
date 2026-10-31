package org.rpgengine.inference.slots;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Chooses among a slot's configured candidates when the primary assignment is unavailable. It is a
 * pure function of the owner's setting and an availability test, so its guarantees are checkable:
 * <ul>
 *   <li>a slot with no configured fallbacks is never served by anything but its primary;</li>
 *   <li>a candidate of another class is never considered, whatever the availability test says;</li>
 *   <li>when no candidate of the slot's class is available the result is {@link Unavailable}, an
 *       explicit refusal, never an assignment of another class or a default model.</li>
 * </ul>
 * A turn that already started is never retried through this class (ADR 0020 decision 3).
 */
public final class SlotFallback {
    /** Fixed code of the explicit refusal. */
    public static final String UNAVAILABLE = "slot-unavailable";

    /** Outcome of one selection. */
    public sealed interface Selection permits Chosen, Unavailable {
    }

    /**
     * A usable candidate.
     *
     * @param candidate the backend, model and effort to use
     * @param fallback whether a configured fallback replaced the primary
     */
    public record Chosen(SlotSetting.Fallback candidate, boolean fallback) implements Selection {
        /** Require the candidate. */
        public Chosen {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /**
     * Nothing of the slot's class is available; the slot stays unavailable.
     *
     * @param code {@value SlotFallback#UNAVAILABLE}
     */
    public record Unavailable(String code) implements Selection {
    }

    private SlotFallback() {
    }

    /**
     * @param setting the owner's assignment
     * @param available whether a candidate can serve right now; called at most once per candidate,
     *        primary first, and not for candidates of another class
     * @return the first available candidate in the owner's order, or {@link Unavailable}
     */
    public static Selection select(SlotSetting setting, Predicate<SlotSetting.Fallback> available) {
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(available, "available");
        SlotSetting.Fallback primary = new SlotSetting.Fallback(setting.backend(), setting.model(), setting.effort());
        if (available.test(primary)) {
            return new Chosen(primary, false);
        }
        for (SlotSetting.Fallback entry : setting.fallbacks()) {
            if (setting.classOf(entry) == setting.backendClass() && available.test(entry)) {
                return new Chosen(entry, true);
            }
        }
        return new Unavailable(UNAVAILABLE);
    }
}
