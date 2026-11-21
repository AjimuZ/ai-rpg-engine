/**
 * NPC world rules evaluated inside the canonical commit (P15.1-A, docs/10 sections 6 and 7): daily
 * schedules that make an NPC's presence a function of in-world time, witnesses that remember where they
 * saw the acting player, and report routes that carry a witness's sightings to other NPCs after a delay
 * in in-world minutes. {@link org.rpgengine.world.NpcSchedule} and {@link org.rpgengine.world.NpcRules}
 * are pure values compiled from pinned content; {@link org.rpgengine.world.NpcWorld} applies them under
 * the campaign lock of a committing operation. Nothing here runs on a read or on the wall clock, so
 * looking at the game never moves an NPC or spreads what someone saw.
 */
package org.rpgengine.world;
