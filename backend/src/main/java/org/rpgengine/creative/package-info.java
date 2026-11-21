/**
 * Optional creative abilities module (P15.4, docs/10 section 9): a player's bounded draft of a new combat ability and its
 * read-only validation preview. Since P15.4-A {@link org.rpgengine.creative.DraftPreview} checks an
 * {@link org.rpgengine.creative.AbilityDraft} against a {@link org.rpgengine.creative.CreativeProfile} and shows the
 * derived cost, targets, durations and what blocks it now. Nothing in this package executes, learns or stores a draft.
 * Since P15.4-B the commands layer calls the preview for the explicit {@code COMBAT_USE_DRAFT} command, whose profile comes
 * from a pack's {@code ruleType: creative} rule, and the combat scheduler resolves the previewed candidate. The exploration
 * command {@code LEARN_DRAFT} previews a draft the same way and stores it as a versioned recipe, which an encounter entry
 * previews again and freezes ({@code commands.LearnedRecipes}); this package still stores nothing itself.
 */
package org.rpgengine.creative;
