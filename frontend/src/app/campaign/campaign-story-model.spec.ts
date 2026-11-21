/** Story entries, quick-reply currency, recipient choice and the SPEAK command of the live story (P17.3-B). */
import { describe, expect, it } from 'vitest';
import { parseOperation } from './campaign-protocol';
import { TrackedOperation } from './campaign-session';
import { MAX_REPLY_TEXT, offeredReplies, replyRecipient, speakCommand, storyEntries } from './campaign-story-model';
import { CAMPAIGN, narratedOperation, OP1, OP2, operation, SPEAKER } from './fixtures';

/** A tracked operation as the session keeps it, built from a body the parser accepted. */
function tracked(body: unknown): TrackedOperation {
  const view = parseOperation(body, CAMPAIGN);
  return { operationId: view.operationId, commandType: view.commandType, status: view.status, operationVersion: view.operationVersion,
    receipt: view.receipt, presentation: view.presentation };
}

describe('Live story model', /** Register pure checks of what the live screen derives from published narration. */ () => {
  it('lists the narrated passages in order with explicit attribution and skips operations without narration', /** Receipts are not story. */ () => {
    const entries = storyEntries([tracked(narratedOperation(OP1)), tracked(operation(OP2, 'COMMITTED_NARRATION_PENDING', 5, true))]);
    expect(entries.map((entry) => [entry.kind, entry.name, entry.actorId])).toEqual([['narration', null, null], ['speech', 'Trail keeper', SPEAKER]]);
    expect(storyEntries([])).toEqual([]);
  });

  it('offers the replies of the newest narration only while the world has not moved and nothing is unfinished', /** Stale replies are withdrawn. */ () => {
    const first = tracked(narratedOperation(OP1));
    expect(offeredReplies([first], 2).map((reply) => [reply.kind, reply.label])).toEqual([
      ['speech', 'Agree'], ['question', 'Ask about the grove'], ['action', 'Look from here']]);
    // The world moved on, or the state is not known yet: nothing is offered.
    expect(offeredReplies([first], 3)).toEqual([]);
    expect(offeredReplies([first], null)).toEqual([]);
    // A later action that is still being processed withdraws them as well.
    expect(offeredReplies([first, tracked(operation(OP2, 'ACCEPTED', 0))], 2)).toEqual([]);
    // After a reload the receipt is unknown until it is re-read, so no reply is trusted.
    expect(offeredReplies([{ ...first, receipt: null }], 2)).toEqual([]);
    // A newer narration without suggestions replaces the older set instead of leaving it visible.
    expect(offeredReplies([first, tracked(narratedOperation(OP2, { suggestions: false, revision: 3, version: 7 }))], 3)).toEqual([]);
    expect(offeredReplies([first, tracked(narratedOperation(OP2, { suggestions: false, revision: 3, version: 7 }))], 2)).toEqual([]);
  });

  it('names a recipient only when a character spoke last', /** The narrator cannot be answered. */ () => {
    const withSpeech = storyEntries([tracked(narratedOperation(OP1))]);
    expect(replyRecipient(withSpeech)).toEqual({ actorId: SPEAKER, name: 'Trail keeper' });
    expect(replyRecipient(storyEntries([tracked(narratedOperation(OP1, { speech: false }))]))).toBeNull();
    expect(replyRecipient([])).toBeNull();
  });

  it('builds SPEAK from the exact words and refuses what the server could not store', /** Nothing is rewritten or completed. */ () => {
    expect(speakCommand(SPEAKER, '  I will come. ')).toEqual({ ok: true, command: { commandType: 'SPEAK', payload: { recipientActorId: SPEAKER, text: '  I will come. ' } } });
    expect(speakCommand(SPEAKER, '   ')).toEqual({ ok: false, refusal: 'EMPTY_TEXT' });
    expect(speakCommand(SPEAKER, 'x'.repeat(MAX_REPLY_TEXT + 1))).toEqual({ ok: false, refusal: 'TEXT_TOO_LONG' });
    expect(speakCommand(SPEAKER, 'x'.repeat(MAX_REPLY_TEXT)).ok).toBe(true);
    expect(speakCommand(SPEAKER, 'bad\u0000text')).toEqual({ ok: false, refusal: 'TEXT_INVALID' });
    expect(speakCommand(SPEAKER, 'lone \uD800 surrogate')).toEqual({ ok: false, refusal: 'TEXT_INVALID' });
    expect(speakCommand(SPEAKER, 'pair 😀 ok').ok).toBe(true);
  });
});
