/**
 * Presentation preferences and locale support: the en/it allowlist, BCP 47 negotiation,
 * and per-campaign narrative locale and presentation generations. Since P07.5 it also owns
 * durable post-commit presentation jobs: one NARRATION job per committed operation, claimed
 * with a fenced lease, narrated through the runtime-neutral {@link
 * org.rpgengine.presentation.NarrationPort} outside transactions and settled together with
 * the journal's presentation status. Nothing here may advance a canonical world counter,
 * write world rows or call the canonical commit service.
 */
package org.rpgengine.presentation;
