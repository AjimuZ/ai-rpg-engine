/**
 * Optional creative abilities module (P15.4, docs/10 section 9): a player's bounded draft of a new combat ability and its
 * read-only validation preview. Since P15.4-A {@link org.rpgengine.creative.DraftPreview} checks an
 * {@link org.rpgengine.creative.AbilityDraft} against a {@link org.rpgengine.creative.CreativeProfile} and shows the
 * derived cost, targets, durations and what blocks it now. Nothing in this package executes, learns or stores a draft, and
 * nothing calls it yet: execution and learning are separate explicit player operations that later stories add.
 */
package org.rpgengine.creative;
