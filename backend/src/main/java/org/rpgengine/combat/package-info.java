/**
 * Encounter lifecycle and frozen tactical state of the default AI RPG Skirmish ruleset (P11.1, ADR 0028).
 * {@code EncounterPhase} is the combat state machine, {@code OpeningPolicy} the slot an opening strike resolves
 * at, {@code EncounterObjective} the pure objective predicates, {@code EncounterTemplate} and {@code CombatRules}
 * the prose-free projection of a pinned pack's encounters and combat profiles, {@code FrozenEncounter} the pure
 * freezing of a template, profile and the player's committed facts at entry, and {@code EncounterWriter} its
 * storage inside a canonical commit. The pure combat profile and zone graph live in {@code org.rpgengine.rules},
 * which the pack compiler also uses. Nothing here reads natural language or draws a die: entry only freezes, and
 * the scheduler, attacks and settlement of later tasks resolve the fight.
 */
package org.rpgengine.combat;
