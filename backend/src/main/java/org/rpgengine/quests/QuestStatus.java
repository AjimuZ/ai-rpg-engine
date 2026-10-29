package org.rpgengine.quests;

import java.util.Arrays;
import java.util.Optional;

/**
 * The quest lifecycle of docs/10 section 5: {@code undiscovered -> available -> active ->
 * completed | failed}, with {@code active <-> suspended}. The wire name is the lowercase pack
 * spelling used by {@code questStatus} predicates and by the {@code quest_state} column.
 *
 * <p>Completed and failed are terminal: a quest never leaves them, so its reward source can be
 * settled only once.
 */
public enum QuestStatus {
    /** Not yet known to the player; the initial state of a quest declared {@code undiscovered}. */
    UNDISCOVERED("undiscovered"),
    /** Known and offered; the player may accept it. */
    AVAILABLE("available"),
    /** Accepted; objectives are tracked and outcomes can fire. */
    ACTIVE("active"),
    /** Set aside by the player; nothing is tracked until it is resumed. */
    SUSPENDED("suspended"),
    /** Ended by a success or alternative outcome. Terminal. */
    COMPLETED("completed"),
    /** Ended by a failure outcome. Terminal. */
    FAILED("failed");

    private final String wire;

    /** @param wire the lowercase spelling shared with the pack language and the database */
    QuestStatus(String wire) {
        this.wire = wire;
    }

    /** @return the lowercase wire name */
    public String wire() {
        return wire;
    }

    /** @return whether the quest can no longer change */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * Parse a wire name.
     *
     * @param wire spelling from a pack or a stored row
     * @return the status, or empty for an unknown spelling
     */
    public static Optional<QuestStatus> parse(String wire) {
        return Arrays.stream(values()).filter(status -> status.wire.equals(wire)).findFirst();
    }
}
