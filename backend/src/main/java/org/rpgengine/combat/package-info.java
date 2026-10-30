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
 * {@code TacticalPlan} the commit writes one row version per step. Nothing here reads natural language.
 *
 * <p>Since P11.3 (ADR 0030) {@code CombatMath} is the pure attack, defense, damage and healing arithmetic,
 * {@code CombatAttack} a frozen attack, {@code EncounterGear} what an equippable item adds at entry, and the scheduler
 * resolves strikes, the Guard action, item use, the opening strike and a small non-player tactic. Dice come only from
 * the recorded draw source it is given.
 *
 * <p>Since P11.4 (ADR 0031) {@code CombatEffect} is a frozen timed effect, {@code CombatEffects} its pure stacking, order and
 * clock rules, {@code CombatReaction} a predeclared reaction with its policy and {@code CombatAbility} a frozen ability, which
 * the scheduler resolves with the same recorded draws. Settlement of a later task completes the fight.
 */
package org.rpgengine.combat;
