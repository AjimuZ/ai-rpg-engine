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
});
