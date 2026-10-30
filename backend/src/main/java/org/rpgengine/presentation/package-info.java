/**
 * Presentation preferences and locale support: the en/it allowlist, BCP 47 negotiation,
 * and per-campaign narrative locale and presentation generations. Since P07.5 it also owns
 * durable post-commit presentation jobs: NARRATION job rounds per committed operation, claimed
 * with a fenced lease, narrated through the runtime-neutral {@link
 * org.rpgengine.presentation.NarrationPort} outside transactions and settled together with
 * the journal's presentation status. Since P08.5 the settling transaction stores validated
 * narration as an immutable publication, the stored player-safe receipt is the deterministic
 * fallback presentation, and narration-only retry rounds re-narrate without re-running the
 * action. Since P05.7-A it also defines the bounded typed public presentation events (scene,
 * pose/expression and line-audio hook): {@link org.rpgengine.presentation.PresentationEvents}
 * admits an untrusted draft only when every reference is on the audience's {@link
 * org.rpgengine.presentation.PublicPresentationScope} and its actors are visible. Since
 * P05.7-B.1.1 {@link org.rpgengine.presentation.PresentationEventStore} stores admitted events
 * durably, which also announces them on the public stream; nothing produces events in a real turn
 * yet, and the browser does not display them. Nothing here may advance a canonical world counter, write world rows or call the
 * canonical commit service, and nothing here depends on inference types.
 */
package org.rpgengine.presentation;
