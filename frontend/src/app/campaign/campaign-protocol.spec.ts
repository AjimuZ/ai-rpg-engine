/** Fail-closed validation of operation bodies, receipts, cursors and stream frames. */
import { describe, expect, it } from 'vitest';
import {
  CampaignProtocolError, compareCursor, FrameDecoder, isPending, MAX_FRAME_CHARS, parseCampaignView, parseCursor, parseOperation, parseReceipt,
  parseResumeCard,
} from './campaign-protocol';
import { ADVENTURE, CAMPAIGN, frame, OP1, OP2, operation, OTHER_CAMPAIGN, receipt, resumeCard, state } from './fixtures';

describe('Campaign protocol', /** Register validators that guard every rendered server value. */ () => {
  it('parses only canonical cursors and orders within a generation', /** Alternate spellings could bypass duplicate detection. */ () => {
    expect(parseCursor('0:12')).toEqual({ generation: 0, sequence: 12 });
    for (const bad of ['01:2', '1:02', '1:-2', '1', '1:2:3', ' 1:2', '1:2 ', '', '9'.repeat(30) + ':1', null, 12]) expect(parseCursor(bad)).toBeUndefined();
    expect(compareCursor({ generation: 1, sequence: 3 }, { generation: 1, sequence: 2 })).toBe(1);
    expect(compareCursor({ generation: 1, sequence: 2 }, { generation: 1, sequence: 2 })).toBe(0);
    expect(compareCursor({ generation: 2, sequence: 1 }, { generation: 1, sequence: 9 })).toBeUndefined();
  });

  it('validates operation bodies and rejects foreign or unknown values', /** A body for another campaign must never render. */ () => {
    expect(parseOperation(operation(OP1, 'COMMITTED', 3, true), CAMPAIGN).receipt?.resources[0]).toEqual({ resourceKey: 'vigor', before: 12000, after: 11000 });
    expect(() => parseOperation(operation(OP1, 'COMMITTED', 3), OTHER_CAMPAIGN)).toThrow(CampaignProtocolError);
    expect(() => parseOperation({ ...operation(OP1, 'EXPLODED', 3) }, CAMPAIGN)).toThrow(CampaignProtocolError);
    expect(() => parseOperation({ ...operation(OP1, 'ACCEPTED', 3), operationId: 'not-a-uuid' }, CAMPAIGN)).toThrow(CampaignProtocolError);
    expect(() => parseOperation({ ...operation(OP1, 'ACCEPTED', -1) }, CAMPAIGN)).toThrow(CampaignProtocolError);
    expect(isPending('COMMITTED')).toBe(true);
    expect(isPending('DELIVERED')).toBe(false);
  });

  it('rejects malformed receipts instead of partially rendering them', /** Numeric effects must arrive exactly or not at all. */ () => {
    expect(parseReceipt(receipt()).randomDraws).toEqual([{ ordinal: 0, sides: 20, value: 17 }]);
    expect(() => parseReceipt({ ...receipt(), stateRevision: 1.5 })).toThrow();
    expect(() => parseReceipt({ ...receipt(), resources: [{ resourceKey: '', before: 1, after: 2 }] })).toThrow();
    expect(() => parseReceipt({ ...receipt(), calculations: [{ path: '/a', kind: 'CHECK', value: 'x', drawOrdinals: [] }] })).toThrow();
    expect(() => parseReceipt('receipt')).toThrow();
  });

  it('decodes chunked frames, ignores heartbeats and rejects unknown fields and sizes', /** Proxy-injected text is not a message. */ () => {
    const decoder = new FrameDecoder(CAMPAIGN);
    const text = 'retry: 1000\n\n' + frame('0:1', 'operation.accepted', { operationId: OP1, commandType: 'WAIT', status: 'ACCEPTED', operationVersion: 0 }) + ':\n\n';
    expect(decoder.push(text.slice(0, 40))).toEqual([]);
    const events = decoder.push(text.slice(40));
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ id: '0:1', type: 'operation.accepted', operationId: OP1, status: 'ACCEPTED' });
    expect(new FrameDecoder(CAMPAIGN).push('id: 0:2\r\nevent: stream.ready\r\ndata: {"campaignId":"' + CAMPAIGN + '","stateRevision":2}\r\n\r\n')[0].type).toBe('stream.ready');
    expect(() => new FrameDecoder(CAMPAIGN).push('id: 0:1\nevent: operation.accepted\n\n')).toThrow();
    expect(() => new FrameDecoder(CAMPAIGN).push('id: 0:1\nevent: nope\ndata: {}\n\n')).toThrow();
    expect(() => new FrameDecoder(CAMPAIGN).push('id: 0:1\nevent: stream.ready\nscript: x\ndata: {}\n\n')).toThrow();
    expect(() => new FrameDecoder(OTHER_CAMPAIGN).push(frame('0:1', 'stream.ready', {}))).toThrow();
    expect(() => new FrameDecoder(CAMPAIGN).push(frame('0:1', 'stream.reset', { reason: 'ANYTHING' }))).toThrow();
    expect(() => new FrameDecoder(CAMPAIGN).push('x'.repeat(MAX_FRAME_CHARS + 1))).toThrow();
    // Narration reasons are an allowlist too.
    expect(() => new FrameDecoder(CAMPAIGN).push(frame('0:3', 'narrative.pending', { operationId: OP2, commandType: 'WAIT', status: 'COMMITTED_NARRATION_PENDING', operationVersion: 4, reason: '<b>x</b>' }))).toThrow();
  });

  it('validates the resume card against its scope and closed vocabularies', /** P09.5: unknown codes never render. */ () => {
    const card = parseResumeCard(resumeCard({ paused: true, awaiting: true }), ADVENTURE, CAMPAIGN);
    expect(card.mutationPaused).toBe(true);
    expect(card.issues).toEqual([{ code: 'DEPENDENCY_MISSING', packId: 'sample:lanterns', packVersion: '1.0.0' }]);
    expect(card.awaitingNarration[0]).toMatchObject({ operationId: OP1, mode: 'RECEIPT', reason: 'NARRATION_UNAVAILABLE', retriesRemaining: 3 });
    expect(card.awaitingNarration[0].receipt?.stateRevision).toBe(2);
    expect(card.location).toEqual({ id: 'trailhead', name: 'Lantern Shelter', nameLocale: 'en', nameFallback: false });
    expect(parseResumeCard({ ...resumeCard(), location: null }, ADVENTURE, CAMPAIGN).location).toBeNull();
    expect(() => parseResumeCard(resumeCard(), ADVENTURE, OTHER_CAMPAIGN)).toThrow(CampaignProtocolError);
    expect(() => parseResumeCard(resumeCard({ decision: 'ANYTHING' }), ADVENTURE, CAMPAIGN)).toThrow();
    const unknownIssue = resumeCard({ paused: true });
    (unknownIssue.dependencies.issues as unknown[]) = [{ code: '<b>x</b>', packId: null, packVersion: null }];
    expect(() => parseResumeCard(unknownIssue, ADVENTURE, CAMPAIGN)).toThrow();
    expect(() => parseResumeCard({ ...resumeCard({ awaiting: true }), awaitingNarrationTotal: 0 }, ADVENTURE, CAMPAIGN)).toThrow();
    expect(() => parseResumeCard({ ...resumeCard(), resources: [{ key: 'vigor', minimum: 0, current: 1.5, maximum: 2 }] }, ADVENTURE, CAMPAIGN)).toThrow();
  });

  it('binds the campaign view to the routed scope and reads the fallback flag', /** A foreign scope must not populate this screen. */ () => {
    expect(parseCampaignView(state(), ADVENTURE, CAMPAIGN).startingSituation).toEqual({ text: 'Una lanterna brilla.', locale: 'it', fallback: true });
    expect(parseCampaignView(state({ situation: false }), ADVENTURE, CAMPAIGN).startingSituation).toBeNull();
    expect(() => parseCampaignView(state(), ADVENTURE, OTHER_CAMPAIGN)).toThrow();
    expect(() => parseCampaignView(state({ locale: 'fr' }), ADVENTURE, CAMPAIGN)).toThrow();
  });
});
