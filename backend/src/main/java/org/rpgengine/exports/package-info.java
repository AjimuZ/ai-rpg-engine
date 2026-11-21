/**
 * Campaign exports (P16.2-A): one owned campaign written as a self-describing JSON document for an explicitly chosen
 * audience.
 *
 * <p>{@link org.rpgengine.exports.CampaignExports} offers two audiences that never share a code path for their data.
 * The full-owner export copies every campaign-scoped canonical row, secrets included, from one repeatable-read
 * snapshot. The player export is assembled only from {@link org.rpgengine.panels.PlayPanels}, the projection that
 * already keeps hidden quests, secret definitions, internal events and other holders' knowledge out of the
 * interface. A player export therefore can show nothing the player panels could not show. Neither export carries
 * account data, and neither writes a row.
 */
package org.rpgengine.exports;
