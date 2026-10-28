/**
 * Presentation preferences and locale support: the en/it allowlist, BCP 47 negotiation,
 * and per-campaign narrative locale and presentation generations. Since P07.5 it also owns
 * durable post-commit presentation jobs: NARRATION job rounds per committed operation, claimed
 * with a fenced lease, narrated through the runtime-neutral {@link
 * org.rpgengine.presentation.NarrationPort} outside transactions and settled together with
 * the journal's presentation status. Since P08.5 the settling transaction stores validated
 * narration as an immutable publication, the stored player-safe receipt is the deterministic
 * fallback presentation, and narration-only retry rounds re-narrate without re-running the
 * action. Nothing here may advance a canonical world counter, write world rows or call the
 * canonical commit service, and nothing here depends on inference types.
 */
package org.rpgengine.presentation;
