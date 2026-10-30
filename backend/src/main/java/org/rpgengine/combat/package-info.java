/**
 * Encounter lifecycle and frozen tactical state of the default AI RPG Skirmish ruleset (P11.1, ADR 0028).
 * {@code EncounterPhase} is the combat state machine, {@code OpeningPolicy} the slot an opening strike resolves
 * at, {@code EncounterObjective} the pure objective predicates, {@code EncounterTemplate} and {@code CombatRules}
 * the prose-free projection of a pinned pack's encounters and combat profiles, {@code FrozenEncounter} the pure
 * freezing of a template, profile and the player's committed facts at entry, and {@code EncounterWriter} its
 * storage inside a canonical commit. The pure combat profile and zone graph live in {@code org.rpgengine.rules},
 * which the pack compiler also uses. Entry only freezes and draws nothing.
 *
 * <p>Since P11.2 (ADR 0029) {@code EncounterAction} is a declared social or scene action, {@code TacticalIntent} the
 * player's tactical intentions, {@code EncounterState} the stored tactical state read back by {@code EncounterWriter},
 * {@code TacticalRules} their pure legality (turn, movement, addressing, speech allowance, declared actions, action
 * points) and {@code CombatScheduler} the pure initiative roll and initiative-round scheduler, whose
 * {@code TacticalPlan} the commit writes one row version per step. Nothing here reads natural language; only the
 * initiative roll draws dice, through the recorded draw source it is given. Attacks, reactions and settlement of
 * later tasks complete the fight.
 */
package org.rpgengine.combat;
