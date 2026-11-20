/**
 * Player panels and journal (P10.5): one authorized, read-only snapshot of everything the interface
 * shows about a playthrough beyond the resume card - the world clock, the character sheet with its
 * conditions, resources and abilities, the inventory with worn equipment, the discovered quests with
 * their objectives and outcomes, what the player knows and has promised, and the history of public
 * events in the campaign's language.
 *
 * <p>{@link org.rpgengine.panels.PlayPanels} reads committed rows only, in a single repeatable-read
 * transaction, so every list describes the same state revision; it never advances time, writes a row
 * or calls a model. Display text comes from the pinned pack through
 * {@link org.rpgengine.panels.PanelContent}, which keeps only text whose source visibility is public.
 * A panel therefore cannot show hidden quests, secret definitions or internal events, and no panel
 * infers a bonus or a restoration from prose: numbers are the stored and recomputed values.
 *
 * <p>Since P12.1 {@link org.rpgengine.panels.CombatPanels} projects the active fight for the combat panel in the same
 * read-only way: initiative, action points, zones, public standings and conditions, the player's own values, defenses known
 * only from public assessments, objectives, legal candidates with costs and targets, and the verdict awaiting settlement.
 * Since P13.2-B it also names, outside a fight, the authored encounters the player may start now, chosen by the
 * {@code ENGAGE} admission gates themselves and limited to what entry reveals.
 */
package org.rpgengine.panels;
