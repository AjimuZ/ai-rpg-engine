/**
 * Owner exports and restores: campaign documents (P16.2-A), reusable adventure bundles for sharing between people
 * (P16.2-B), and the restore of a full-owner campaign export into a named adventure (P16.4-A).
 *
 * <p>{@link org.rpgengine.exports.CampaignExports} offers two audiences that never share a code path for their data.
 * The full-owner export copies every campaign-scoped canonical row, secrets included, from one repeatable-read
 * snapshot. The player export is assembled only from {@link org.rpgengine.panels.PlayPanels}, the projection that
 * already keeps hidden quests, secret definitions, internal events and other holders' knowledge out of the
 * interface. A player export therefore can show nothing the player panels could not show. Neither export carries
 * account data, and neither writes a row.
 *
 * <p>{@link org.rpgengine.exports.AdventureBundles} exports a sealed revision and its pinned dependency closure as one
 * ZIP built only from sealed pack files, with a canonical integrity, dependency, locale and rights manifest. It
 * previews and imports received bundles through the pack staging and registration boundaries, into a new workspace
 * or an owned destination, and never overwrites an installed revision or touches a campaign.
 *
 * <p>{@link org.rpgengine.exports.CampaignRestores} turns a full-owner export back into a campaign: always a new one,
 * in the adventure the owner names, pinned to that adventure's sealed revision with the same content hash, with fresh
 * identities unless the owner keeps the original ones.
 */
package org.rpgengine.exports;
