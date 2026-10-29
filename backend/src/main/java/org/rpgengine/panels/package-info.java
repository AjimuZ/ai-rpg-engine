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
 */
package org.rpgengine.panels;
