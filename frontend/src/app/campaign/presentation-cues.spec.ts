/** Cue state: ordinal order inside an operation, repeated deliveries dropped, bounded size, asset path. */
import { describe, expect, it } from 'vitest';
import { PresentationCue } from './campaign-protocol';
import { OP1, OP2 } from './fixtures';
import { cueAssetPath, CueState, MAX_CUES, MAX_SEEN_DELIVERIES } from './presentation-cues';

/** A validated-shape cue; the delivery identity follows the stream rule `<cursor>:<operation>:<ordinal>`. */
function cue(ordinal: number, operationId = OP1, cursor = `0:${ordinal + 1}`, kind: PresentationCue['kind'] = 'SCENE'): PresentationCue {
  return { deliveryId: `${cursor}:${operationId}:${ordinal}`, kind, ordinal, sceneId: 'harbour', actorId: null, poseId: null,
    expressionId: null, lineId: null, assetId: null };
}

describe('CueState', /** Register the ordering, deduplication and bound checks. */ () => {
  it('keeps the cues of an operation in ordinal order whatever the arrival order', /** Cues can arrive out of order. */ () => {
    const state = new CueState();
    for (const ordinal of [2, 0, 1]) expect(state.add(OP1, cue(ordinal))).toBe(true);
    expect(state.cues().map((entry) => entry.ordinal)).toEqual([0, 1, 2]);
  });

  it('keeps operations in arrival order and never interleaves their cues', /** Ordinals restart in each operation. */ () => {
    const state = new CueState();
    state.add(OP1, cue(1, OP1, '0:2'));
    state.add(OP2, cue(0, OP2, '0:3'));
    state.add(OP1, cue(0, OP1, '0:4'));
    expect(state.cues().map((entry) => entry.deliveryId)).toEqual([`0:4:${OP1}:0`, `0:2:${OP1}:1`, `0:3:${OP2}:0`]);
  });

  it('drops a repeated deliveryId without changing the order', /** At-least-once delivery after a reconnect. */ () => {
    const state = new CueState();
    state.add(OP1, cue(0));
    state.add(OP1, cue(1));
    expect(state.add(OP1, cue(0))).toBe(false);
    expect(state.add(OP1, { ...cue(1), sceneId: 'changed' })).toBe(false);
    expect(state.cues()).toHaveLength(2);
    expect(state.cues()[1].sceneId).toBe('harbour');
  });

  it('bounds the kept cues, dropping the oldest, and still refuses a late repeat of a dropped cue', /** Memory stays bounded. */ () => {
    const state = new CueState();
    for (let index = 0; index < MAX_CUES + 5; index++) state.add(OP1, cue(index));
    const kept = state.cues();
    expect(kept).toHaveLength(MAX_CUES);
    expect(kept[0].ordinal).toBe(5);
    expect(state.add(OP1, cue(0))).toBe(false);
    expect(state.cues()).toHaveLength(MAX_CUES);
  });

  it('forgets the oldest delivery identities beyond its memory bound', /** The seen set is bounded too. */ () => {
    const state = new CueState();
    for (let index = 0; index <= MAX_SEEN_DELIVERIES; index++) state.add(OP2, cue(index, OP2, `0:${index + 1}`));
    // The very first identity fell out of the memory, so it is admitted again; a recent one is still refused.
    expect(state.add(OP2, cue(0, OP2, '0:1'))).toBe(true);
    expect(state.add(OP2, cue(MAX_SEEN_DELIVERIES, OP2, `0:${MAX_SEEN_DELIVERIES + 1}`))).toBe(false);
  });

  it('returns snapshots that later additions do not mutate', /** Views compare snapshots by identity. */ () => {
    const state = new CueState();
    state.add(OP1, cue(0));
    const before = state.cues();
    state.add(OP1, cue(1));
    expect(before).toHaveLength(1);
    expect(state.cues()).toHaveLength(2);
  });
});

describe('cueAssetPath', /** The only URL the cue display ever builds. */ () => {
  it('builds a same-origin relative path from a plain reference', () => {
    expect(cueAssetPath('line-01.v2')).toBe('/api/v1/assets/line-01.v2');
    expect(cueAssetPath('a b/../c')).toBe('/api/v1/assets/a%20b%2F..%2Fc');
    expect(cueAssetPath('x')).not.toContain('://');
  });
});
