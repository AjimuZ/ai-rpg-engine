package org.rpgengine.characters;

/** Broad kind of an actor instance; finer classification comes from content definitions. */
public enum ActorKind {
    /** Owner-controlled character. */
    CHARACTER,
    /** Non-player character with social state. */
    NPC,
    /** Creature or other combatant without social state. */
    CREATURE
}
