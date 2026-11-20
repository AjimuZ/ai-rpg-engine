/**
 * Reward ledger with per-campaign source/recipient settlement identity, and since P13.4-A the pure planning of once-only
 * challenge-source rewards ({@link org.rpgengine.progression.ChallengeRewards}) and exact loot ownership
 * ({@link org.rpgengine.progression.EncounterLoot}), which no settlement path calls yet. Since P13.4-B
 * {@link org.rpgengine.progression.Advancement} turns settled XP into levels, records each level's entitlements once and
 * spends them only through validated purchases; nothing calls it yet either. Since P13.5-A
 * {@link org.rpgengine.progression.AdvancementPreview} shows the cost and effect of purchases and compares proposed
 * sequences without ever changing a standing or a build.
 */
package org.rpgengine.progression;
