/** Verify local preview boundaries; no transport or canonical writes are permitted. */
import { describe, expect, it } from 'vitest';
import { PreviewState } from './preview-state';

describe('Preview state', /** Register bounded presentation-state checks. */ () => {
  it('bounds reading and keeps independent positions per adventure', /** Advance beyond the end; positions remain independent and bounded. */ () => {
    const state = new PreviewState();
    expect(state.position('mosslight')).toBe(0);
    for (let i = 0; i < 20; i++) state.advance('mosslight');
    expect(state.position('mosslight')).toBe(1);
    expect(state.position('relay')).toBe(0);
    state.advance('relay');
    expect(state.position('relay')).toBe(1);
    expect(state.find('missing')).toBeUndefined();
    state.advance('missing');
    expect(state.position('missing')).toBe(0);
  });

  it('validates names and refuses unknown identities', /** Only bounded literal names are accepted; invalid values return undefined. */ () => {
    const state = new PreviewState();
    expect(state.previewCharacter('mosslight', '   ')).toBeUndefined();
    expect(state.previewCharacter('mosslight', 'x'.repeat(61))).toBeUndefined();
    expect(state.previewCharacter('missing', 'River')).toBeUndefined();
    expect(state.previewCharacter('relay', ' <b>River</b> ')).toEqual({ name: '<b>River</b>', adventureId: 'relay' });
  });

  it('accepts only finite reading sizes in the documented range', /** Invalid settings leave the current layout unchanged. */ () => {
    const state = new PreviewState();
    for (const value of [NaN, Infinity, 14, 25]) state.setTextSize(value);
    expect(state.textSize()).toBe(18);
    state.setTextSize(24);
    expect(state.textSize()).toBe(24);
  });

  it('accepts only known spacing and width tokens, including no inherited names', /** Tokens are chosen by ID so no value reaches CSS unvalidated. */ () => {
    const state = new PreviewState();
    expect([state.leading(), state.measureRem()]).toEqual([1.65, 48]);
    for (const value of ['', 'tight', 'constructor', '__proto__', 'toString', '1.2']) {
      state.setReadingSpacing(value);
      state.setReadingMeasure(value);
    }
    expect([state.readingSpacing(), state.readingMeasure()]).toEqual(['comfortable', 'standard']);
    state.setReadingSpacing('relaxed');
    state.setReadingMeasure('narrow');
    expect([state.leading(), state.measureRem()]).toEqual([1.9, 36]);
  });

  it('restores saved tokens, treats older snapshots as defaults and rejects an unknown token', /** A snapshot from before the tokens stays valid; a bad token is invalid as a whole. */ () => {
    const store = new Map<string, string>();
    const storage = { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => { store.set(key, value); }, removeItem: (key: string) => { store.delete(key); } };
    const first = new PreviewState(storage);
    first.setReadingSpacing('compact');
    first.setReadingMeasure('wide');
    const restored = new PreviewState(storage);
    expect([restored.readingSpacing(), restored.readingMeasure(), restored.persistence()]).toEqual(['compact', 'wide', 'saved']);
    store.set('engine.preview-reading.v1', JSON.stringify({ version: 1, size: 20, layout: 'text-focused', positions: {} }));
    const older = new PreviewState(storage);
    expect([older.textSize(), older.readingLayout(), older.readingSpacing(), older.readingMeasure(), older.persistence()])
      .toEqual([20, 'text-focused', 'comfortable', 'standard', 'saved']);
    store.set('engine.preview-reading.v1', JSON.stringify({ version: 1, size: 20, layout: 'visual-novel', spacing: 'constructor', positions: {} }));
    const invalid = new PreviewState(storage);
    expect([invalid.textSize(), invalid.readingSpacing(), invalid.persistence()]).toEqual([18, 'comfortable', 'invalid']);
  });
});
