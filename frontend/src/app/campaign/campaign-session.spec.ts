/** Durable operation/cursor reconciliation across simulated reloads: dedup, resume, reset and storage loss. */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { CampaignSession } from './campaign-session';
import { CampaignServer, CAMPAIGN, frame, MemoryStore, OP1, OP2, operation, SCOPE, syncProvider } from './fixtures';

/** Build a fresh injector sharing one storage and server, simulating a page reload. */
function load(store: MemoryStore, server: CampaignServer): CampaignSession {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [syncProvider(store), { provide: LIBRARY_FETCH, useValue: server.fetch }] });
  return TestBed.inject(CampaignSession);
}
const accepted = (id: string, op: string, version = 0) => frame(id, 'operation.accepted', { operationId: op, commandType: 'WAIT', status: 'ACCEPTED', operationVersion: version });

describe('Campaign session reconciliation', /** Register reload-safety checks for operation identity and stream cursor. */ () => {
  let store: MemoryStore;
  let server: CampaignServer;
  const signal = new AbortController().signal;
  beforeEach(/** Isolate storage and the scripted server. */ () => { store = new MemoryStore(); server = new CampaignServer(); });

  it('persists cursor and operation identities and resumes after a reload without duplicating delivery', /** EN→IT→EN keeps identity and position. */ async () => {
    server.streams = [[accepted('0:1', OP1), frame('0:2', 'state.committed', { operationId: OP1, commandType: 'WAIT', status: 'COMMITTED', operationVersion: 2, receipt: { stateRevision: 2, firstEventSequence: 1, lastEventSequence: 2, randomDraws: [] } })]];
    let session = load(store, server);
    expect(await session.reconcile(SCOPE, signal)).toMatchObject({ applied: 2, duplicates: 0 });
    expect(session.cursor(SCOPE)).toBe('0:2');
    expect(session.verified(SCOPE)).toBe(true);

    // Reload: a new injector reads only the persisted bytes; the committed operation is still pending narration.
    server.calls.length = 0;
    server.operations.set(OP1, operation(OP1, 'DELIVERED', 4, true));
    server.streams = [[accepted('0:1', OP1), frame('0:2', 'state.committed', { operationId: OP1, commandType: 'WAIT', status: 'COMMITTED', operationVersion: 2, receipt: { stateRevision: 2, firstEventSequence: 1, lastEventSequence: 2, randomDraws: [] } })]];
    session = load(store, server);
    expect(session.cursor(SCOPE)).toBe('0:2');
    expect(session.operations(SCOPE).map((o) => [o.operationId, o.status])).toEqual([[OP1, 'COMMITTED']]);
    const result = await session.reconcile(SCOPE, signal);
    // The pending operation was re-queried by identity; the replayed frames were recognised as duplicates.
    expect(server.calls.some((url) => url.includes(`/operations/${OP1}?`))).toBe(true);
    expect(result).toMatchObject({ applied: 0, duplicates: 2 });
    expect(session.operations(SCOPE)).toHaveLength(1);
    expect(session.operations(SCOPE)[0]).toMatchObject({ status: 'DELIVERED', operationVersion: 4 });
    expect(session.operations(SCOPE)[0].receipt?.stateRevision).toBe(2);
    // The resumed stream request carried the saved cursor, never a submission.
    expect(server.calls.filter((url) => url.includes('/stream?')).every((url) => url.includes('after=0%3A2') || url.includes('after=0:2'))).toBe(true);
    expect(server.calls.every((url) => !url.includes('/commands'))).toBe(true);
  });

  it('keeps operations oldest first when a reload re-reads only an earlier one', /** A receipt re-read must not reorder the Actions list. */ async () => {
    store.setItem(`engine.campaign-sync.v1:${JSON.stringify([SCOPE.adventureId, SCOPE.campaignId])}`, JSON.stringify({ version: 1, cursor: '0:2', operations: [
      { operationId: OP1, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 4 },
      { operationId: OP2, commandType: 'WAIT', status: 'CANCELLED', operationVersion: 3 }] }));
    server.operations.set(OP1, operation(OP1, 'DELIVERED', 4, true));
    server.streams = [[]];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    expect(server.calls.some((url) => url.includes(`/operations/${OP2}?`))).toBe(false);
    expect(session.operations(SCOPE)[0].receipt?.stateRevision).toBe(2);
    expect(session.operations(SCOPE).map((o) => o.operationId)).toEqual([OP1, OP2]);
  });

  it('starts at the head on first contact and does not replay history', /** stream.ready positions a fresh cursor. */ async () => {
    server.streams = [[frame('0:7', 'stream.ready', {})]];
    const session = load(store, server);
    const result = await session.reconcile(SCOPE, signal);
    expect(result.applied).toBe(0);
    expect(session.cursor(SCOPE)).toBe('0:7');
  });

  it('applies a stream reset by adopting the head and re-querying pending work', /** A missed window must not hide a finished action. */ async () => {
    server.streams = [[accepted('0:1', OP2)], []];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    server.operations.set(OP2, operation(OP2, 'DELIVERED', 5, true));
    server.streams = [[frame('0:50', 'stream.reset', { reason: 'CURSOR_EXPIRED' })]];
    const result = await session.reconcile(SCOPE, signal);
    expect(result.reset).toBe('CURSOR_EXPIRED');
    expect(session.cursor(SCOPE)).toBe('0:50');
    expect(session.operations(SCOPE)[0].status).toBe('DELIVERED');
  });

  it('replaces an unknown cursor that is ahead of the head instead of merging it back', /** A stale larger cursor must not cause a reset loop. */ async () => {
    // A saved position beyond the server head (for example after restoring an older database copy).
    store.setItem(`engine.campaign-sync.v1:${JSON.stringify([SCOPE.adventureId, SCOPE.campaignId])}`,
      JSON.stringify({ version: 1, cursor: '0:99', operations: [] }));
    server.streams = [[frame('0:7', 'stream.reset', { reason: 'CURSOR_UNKNOWN' })], []];
    const session = load(store, server);
    expect((await session.reconcile(SCOPE, signal)).reset).toBe('CURSOR_UNKNOWN');
    expect(session.cursor(SCOPE)).toBe('0:7');
    expect(session.verified(SCOPE)).toBe(true);
    // The next poll resumes after the adopted head, and a reloaded page reads the same position.
    server.calls.length = 0;
    expect((await session.poll(SCOPE, signal)).reset).toBeNull();
    expect(server.calls.filter((url) => url.includes('/stream?')).map((url) => decodeURIComponent(url))).toEqual([
      `/api/v1/campaigns/${SCOPE.campaignId}/stream?adventureId=${SCOPE.adventureId}&after=0:7`]);
    expect(load(store, server).cursor(SCOPE)).toBe('0:7');
  });

  it('reports a generation change instead of applying messages from another generation', /** Restored backups need a reset first. */ async () => {
    server.streams = [[accepted('0:1', OP1)]];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    server.operations.set(OP1, operation(OP1, 'ACCEPTED', 0));
    server.streams = [[accepted('1:1', OP2)]];
    const result = await session.reconcile(SCOPE, signal);
    expect(result.reset).toBe('GENERATION_CHANGED');
    expect(session.operations(SCOPE).map((o) => o.operationId)).toEqual([OP1]);
  });

  it('refuses reload verification when storage is unavailable or reverts, and ignores corrupt records', /** Lost cursors must block the language switch. */ async () => {
    server.streams = [[accepted('0:1', OP1)]];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    store.denied = true;
    expect(session.verified(SCOPE)).toBe(false);
    expect(session.persistence()).toBe('unavailable');
    store.denied = false;
    expect(session.verified(SCOPE)).toBe(true);

    const key = [...store.values.keys()][0];
    store.values.set(key, '{not json');
    expect(load(store, server).cursor(SCOPE)).toBeNull();
    store.values.set(key, JSON.stringify({ version: 1, cursor: '0:3', operations: [{ operationId: 'x', commandType: 'WAIT', status: 'ACCEPTED', operationVersion: 0 }] }));
    expect(load(store, server).operations(SCOPE)).toEqual([]);
  });

  it('never regresses a cursor that another tab advanced', /** Persisting merges rather than overwrites. */ async () => {
    server.streams = [[accepted('0:1', OP1)]];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    const key = [...store.values.keys()][0];
    store.values.set(key, JSON.stringify({ version: 1, cursor: '0:9', operations: [{ operationId: OP2, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 7 }] }));
    expect(session.verified(SCOPE)).toBe(true);
    expect(session.cursor(SCOPE)).toBe('0:9');
    expect(session.operations(SCOPE).map((o) => o.operationId).sort()).toEqual([OP1, OP2].sort());
  });

  it('keeps scopes separate and bounds tracked operations', /** Equal IDs in another campaign never merge. */ async () => {
    server.streams = [[accepted('0:1', OP1)]];
    const session = load(store, server);
    await session.reconcile(SCOPE, signal);
    expect(session.operations({ ...SCOPE, campaignId: CAMPAIGN.replace(/1$/, '3') })).toEqual([]);
    for (let n = 0; n < 60; n++) {
      session.applyOperation(SCOPE, { ...operation(`00000000-0000-4000-8000-${String(n).padStart(12, '0')}`, 'DELIVERED', 1), receipt: null } as never);
    }
    expect(session.operations(SCOPE).length).toBeLessThanOrEqual(50);
  });
});
