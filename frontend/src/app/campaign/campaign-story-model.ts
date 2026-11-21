/**
 * Pure model of the live story reading (P17.3-B): the attributed passages the playthrough screen shows, the quick replies it
 * may offer, and the one command a reply draft can become.
 *
 * Everything here derives from what the server already published (an operation's `presentation`) and decides nothing about the
 * world: a quick reply is only draft text, a command is built only from words the player wrote and a recipient the server named,
 * and the server still validates, orders and deduplicates every submission.
 */
import { isPending, NarrationSuggestion } from './campaign-protocol';
import { TrackedOperation } from './campaign-session';
import { TacticalCommand } from './combat-actions';

/** One attributed passage of the reading, narrator or character, in the order the operations were committed. */
export interface StoryEntry {
  readonly id: string;
  readonly kind: 'narration' | 'speech';
  /** The speaking actor, or null for the narrator. */
  readonly actorId: string | null;
  /** The speaker's public name, or null for the narrator (the screen supplies the localized narrator label). */
  readonly name: string | null;
  readonly text: string;
  readonly locale: 'en' | 'it';
}

/** The quick-reply kinds shown to the player; the label of each kind is localized by the screen. */
export type ReplyKind = 'speech' | 'question' | 'action';

/** One offered quick reply: the chip label and the editable draft it fills. */
export interface OfferedReply {
  readonly id: string;
  readonly kind: ReplyKind;
  readonly label: string;
  readonly draftText: string;
}

/**
 * Every published passage of the tracked operations, oldest operation first, in the server's order inside each operation.
 * Operations whose narration is absent, in progress or only a receipt contribute nothing: their result stays in the actions list.
 */
export function storyEntries(operations: readonly TrackedOperation[]): StoryEntry[] {
  const entries: StoryEntry[] = [];
  for (const operation of operations) {
    if (operation.presentation?.mode !== 'NARRATED') continue;
    for (const passage of operation.presentation.passages) {
      entries.push({ id: passage.messageId, kind: passage.kind === 'CHARACTER_SPEECH' ? 'speech' : 'narration',
        actorId: passage.speaker?.actorId ?? null, name: passage.speaker?.name ?? null, text: passage.text, locale: passage.locale });
    }
  }
  return entries;
}

/** Map the server's suggestion kinds onto the three labelled reply kinds. */
function replyKind(kind: NarrationSuggestion['kind']): ReplyKind {
  return kind === 'REPLY' ? 'speech' : kind === 'QUESTION' ? 'question' : 'action';
}

/**
 * The quick replies that are still current.
 *
 * Contract: only the newest narrated operation can offer replies, and only while the world is still at the revision that
 * operation committed and no later operation is unfinished. Any newer commit or pending action therefore withdraws them, and a
 * reload never resurrects an older set. No reply is a valid outcome and never blocks the composer.
 *
 * @param stateRevision the world revision of the shown state
 */
export function offeredReplies(operations: readonly TrackedOperation[], stateRevision: number | null): OfferedReply[] {
  if (stateRevision === null) return [];
  let index = operations.length - 1;
  while (index >= 0 && operations[index].presentation?.mode !== 'NARRATED') index--;
  if (index < 0) return [];
  const source = operations[index];
  if (source.receipt?.stateRevision !== stateRevision || operations.slice(index + 1).some((later) => isPending(later.status))) return [];
  return source.presentation!.suggestions.map((suggestion) => ({ id: suggestion.suggestionId, kind: replyKind(suggestion.kind),
    label: suggestion.label, draftText: suggestion.draftText }));
}

/** The person the player's words go to: the speaker of the newest passage, or null when the narrator spoke last. */
export function replyRecipient(entries: readonly StoryEntry[]): { readonly actorId: string; readonly name: string } | null {
  const last = entries.at(-1);
  return last !== undefined && last.kind === 'speech' && last.actorId !== null && last.name !== null ? { actorId: last.actorId, name: last.name } : null;
}

/** Longest wording the server accepts for speech, in UTF-16 units (docs/07, typed `SPEAK`). */
export const MAX_REPLY_TEXT = 4000;
/** Stable refusal codes of the page's own check; they carry no server text. */
export type ReplyRefusal = 'EMPTY_TEXT' | 'TEXT_TOO_LONG' | 'TEXT_INVALID';
/** The server refuses U+0000 and unpaired surrogates, which no stored text can hold. */
const UNSTORABLE = /\u0000|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/;

/**
 * Build the `SPEAK` command for the player's own words.
 *
 * Contract: the text is sent exactly as written (never trimmed, rewritten or completed) and only when it is meaningful, within
 * the limit and storable; otherwise the refusal is returned and nothing may be sent.
 */
export function speakCommand(recipientActorId: string, text: string): { readonly ok: true; readonly command: TacticalCommand } | { readonly ok: false; readonly refusal: ReplyRefusal } {
  if (text.trim() === '') return { ok: false, refusal: 'EMPTY_TEXT' };
  if (text.length > MAX_REPLY_TEXT) return { ok: false, refusal: 'TEXT_TOO_LONG' };
  if (UNSTORABLE.test(text)) return { ok: false, refusal: 'TEXT_INVALID' };
  return { ok: true, command: { commandType: 'SPEAK', payload: { recipientActorId, text } } };
}
