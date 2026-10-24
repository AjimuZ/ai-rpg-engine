/** Verify scoped presentation state and durable drafts without transport or world actions. */
import { describe, expect, it } from 'vitest';
import { StoryInputState, DraftStorage, StoryProjection } from './story-input-state';

const scope = { adventureId: 'mosslight', campaignId: 'local-preview' };
/** Supply isolated browser-like storage; absent keys return null and writes stay local. */
function storage(): DraftStorage {
  const entries = new Map<string, string>();
  return {
    getItem: /** Read a fixture key without inventing a value. */ (key) => entries.get(key) ?? null,
    setItem: /** Persist fixture bytes for a later service instance. */ (key, value) => { entries.set(key, value); },
  };
}
/** Build a public-only fixture; overrides model reordered deliveries, never executable content. */
function projection(overrides: Partial<StoryProjection> = {}): StoryProjection {
  return { ...scope, revision: 4, presentationRevision: 1,
    suggestions: [{ id: 'ask', kind: 'question', text: 'Ask about the trail.' }], ...overrides };
}

describe('Story input state', /** Register deterministic delivery and persistence boundaries. */ () => {
  it('rejects obsolete scopes, generations, revisions and duplicate deliveries', /** Reordering cannot replace accepted context or drafts. */ () => {
    const state = new StoryInputState(storage());
    const epoch = state.activate(scope);
    expect(state.accept(projection(), epoch)).toBe(true);
    state.edit('My own words', 3, 7);
    expect(state.accept(projection(), epoch)).toBe(false);
    expect(state.accept(projection({ revision: 3, presentationRevision: 9 }), epoch)).toBe(false);
    expect(state.accept(projection({ campaignId: 'other', revision: 9 }), epoch)).toBe(false);
    expect(state.accept(projection({ revision: NaN }), epoch)).toBe(false);
    expect(state.projection()?.revision).toBe(4);
    expect(state.draft().text).toBe('My own words');
    state.activate({ ...scope, campaignId: 'other' });
    const current = state.activate(scope);
    expect(state.accept(projection({ revision: 9 }), epoch)).toBe(false);
    expect(state.accept(projection({ revision: 3 }), current)).toBe(false);
    expect(state.accept(projection({ revision: 5 }), current)).toBe(true);
  });

  it('invalidates callbacks and reply references when the story screen is detached', /** Returning to the same scope must not reactivate an old event closure. */ () => {
    const state = new StoryInputState(storage());
    const epoch = state.activate(scope);
    state.accept(projection(), epoch);
    const reply = state.suggestions()[0]!;
    state.deactivate(epoch);
    expect(state.accept(projection({ revision: 5 }), epoch)).toBe(false);
    expect(state.choose(reply)).toBe(false);
    const current = state.activate(scope);
    state.setStatus('ready', current);
    expect(current).toBeGreaterThan(epoch);
    expect(state.projection()?.revision).toBe(4);
    expect(state.choose(reply)).toBe(false);
    expect(state.choose(state.suggestions()[0]!)).toBe(true);
    state.deactivate(epoch);
    expect(state.status()).toBe('ready');
  });

  it('keeps drafts and selections across reload, campaign and adventure switches', /** Structured keys cannot collide when IDs contain punctuation. */ () => {
    const disk = storage();
    const state = new StoryInputState(disk);
    state.activate(scope);
    state.edit('First draft', 2, 5);
    state.activate({ ...scope, campaignId: 'second' });
    expect(state.draft().text).toBe('');
    state.edit('Second draft', 0, 0);
    state.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    expect(state.draft().text).toBe('');
    const restored = new StoryInputState(disk);
    restored.activate(scope);
    expect(restored.draft()).toEqual({ text: 'First draft', start: 2, end: 5 });
    restored.edit('');
    const cleared = new StoryInputState(disk);
    cleared.activate(scope);
    expect(cleared.draft().text).toBe('');
    restored.activate(scope);
    expect(restored.draft().text).toBe('');
  });

  it('selects only current suggestions into an empty draft and dismisses without editing', /** Repeated/stale chips never overwrite authored text or advance revisions. */ () => {
    const state = new StoryInputState(storage());
    const epoch = state.activate(scope);
    state.accept(projection(), epoch);
    expect(state.choose(state.projection()!.suggestions[0]!)).toBe(true);
    expect(state.draft().text).toBe('Ask about the trail.');
    state.edit('Edited response');
    expect(state.choose(state.projection()!.suggestions[0]!)).toBe(false);
    state.dismiss('ask');
    expect(state.suggestions()).toEqual([]);
    expect(state.draft().text).toBe('Edited response');
    expect(state.accept(projection(), epoch)).toBe(false);
    expect(state.suggestions()).toEqual([]);
    const obsoleteReply = state.projection()!.suggestions[0]!;
    state.accept(projection({ revision: 5, presentationRevision: 0 }), epoch);
    state.edit('');
    expect(state.choose(obsoleteReply)).toBe(false);
    state.edit('Edited response');
    expect(state.draft().text).toBe('Edited response');
    expect(state.projection()?.revision).toBe(5);
  });

  it('retains the last projection and draft through every recovery state', /** Reconnect never replays input or enables implicit submission. */ () => {
    const state = new StoryInputState(storage());
    const epoch = state.activate(scope);
    state.accept(projection(), epoch);
    state.edit('Keep this');
    for (const status of ['disconnected', 'reconnecting', 'error', 'stale-revision',
      'clarification', 'cancelled', 'narration-pending', 'restore-required'] as const) {
      state.setStatus(status, epoch);
      expect(state.status()).toBe(status);
      expect(state.projection()?.revision).toBe(4);
      expect(state.draft().text).toBe('Keep this');
      state.edit('');
      expect(state.choose(state.projection()!.suggestions[0]!)).toBe(false);
      state.edit('Keep this');
    }
    state.activate({ ...scope, campaignId: 'new' });
    state.setStatus('error', epoch);
    expect(state.status()).toBe('loading');
  });

  it('bounds untrusted drafts and suggestions and survives denied or corrupt storage', /** Storage faults are visible and never erase an in-memory edit. */ () => {
    const denied: DraftStorage = {
      getItem: /** Simulate storage policy denial. */ () => { throw new Error('denied'); },
      setItem: /** Simulate quota failure. */ () => { throw new Error('quota'); },
    };
    const state = new StoryInputState(denied);
    const epoch = state.activate(scope);
    state.edit('x'.repeat(4100), Infinity, -2);
    expect(state.draft().text.length).toBe(4000);
    expect(state.draft().start).toBe(4000);
    expect(state.draft().end).toBe(4000);
    expect(state.persistence()).toBe('unavailable');
    for (const suggestions of [
      [{ id: 'bad', kind: 'action' as const, text: 'x'.repeat(241) }],
      Array.from({ length: 4 }, /** Supply too many otherwise valid replies. */ (_, index) => ({ id: String(index), kind: 'action' as const, text: 'Look around.' })),
      [{ id: 'same', kind: 'question' as const, text: 'Why?' }, { id: 'same', kind: 'question' as const, text: 'Where?' }],
    ]) expect(state.accept(projection({ suggestions }), epoch)).toBe(false);
    state.activate({ ...scope, campaignId: 'other' });
    state.activate(scope);
    expect(state.persistence()).toBe('unavailable');
    const corrupt = new StoryInputState({ ...storage(), getItem: /** Return incompatible persisted data. */ () => '{"version":9}' });
    corrupt.activate(scope);
    expect(corrupt.draft().text).toBe('');
    expect(corrupt.persistence()).toBe('invalid');
  });
});
