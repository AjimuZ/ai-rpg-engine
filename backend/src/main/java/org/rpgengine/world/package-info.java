/**
 * NPC world rules evaluated inside the canonical commit (P15.1-A, docs/10 sections 6 and 7): daily
 * schedules that make an NPC's presence a function of in-world time, witnesses that remember where they
 * saw the acting player, and report routes that carry a witness's sightings to other NPCs after a delay
 * in in-world minutes. {@link org.rpgengine.world.NpcSchedule} and {@link org.rpgengine.world.NpcRules}
 * are pure values compiled from pinned content; {@link org.rpgengine.world.NpcWorld} applies them under
 * the campaign lock of a committing operation. Since P15.1-B the optional {@code relationships} module adds
 * {@link org.rpgengine.world.RelationshipRules} (bounded dimensions, typed causes, a companion threshold) and
 * {@link org.rpgengine.world.Relationships}, which records each change with the event that caused it and moves
 * companions with the player. Nothing here runs on a read or on the wall clock, so looking at the game never
 * moves an NPC, spreads what someone saw or changes how anyone regards the player.
 */
package org.rpgengine.world;
