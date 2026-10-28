package org.rpgengine.inference.contract;

/**
 * Intentions an intent proposal may name. They mirror the registered
 * {@link org.rpgengine.commands.TypedCommand} types exactly; an intention without a typed
 * command contract (items, trade, encounter actions) cannot be proposed.
 */
public enum IntentKind {
    /** Move to one known destination. */
    MOVE,
    /** Wait for a bounded number of seconds. */
    WAIT,
    /** Say the player's own words to one known recipient. */
    SPEAK,
    /** Use one learned ability on permitted targets. */
    USE_ABILITY
}
