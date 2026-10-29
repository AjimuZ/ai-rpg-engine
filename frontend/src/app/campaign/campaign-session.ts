/**
 * Read-only campaign transport and durable operation/stream-cursor reconciliation.
 *
 * {@link CampaignTransport} issues same-origin GETs (campaign state, the resume card, one
 * operation's status, one bounded stream long poll) and validates every reply. {@link CampaignSession} keeps, per
 * adventure+campaign scope, the last delivered stream cursor and the identities, versions and
 * statuses of the operations seen so far. The record is written through to device storage so a
 * page reload — notably the explicit EN↔IT language reload — can resume exactly where it
 * stopped: pending operations are re-queried by identity and the stream resumes after the saved
 * cursor. Delivery is deduplicated by cursor and operation version, so replays after a reload
 * never apply a message twice. Nothing in this module submits a command or a draft.
 */
import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { SavedLibraryClient, LIBRARY_FETCH } from '../library/saved-library-client';
import {
  CampaignProtocolError, CampaignView, compareCursor, Cursor, FrameDecoder, isPending, OPERATION_STATUSES,
  OperationStatus, OperationView, parseCampaignView, parseCursor, parseOperation, parsePlayPanels, parseResumeCard, PlayPanels, PublicReceipt, ResumeCard,
  StreamEvent,
} from './campaign-protocol';

/** Adventure and campaign identity; display names never address a record. */
export interface CampaignScope { readonly adventureId: string; readonly campaignId: string }

/** Device storage for reconciliation records; null keeps state in memory and blocks language reload. */
export const CAMPAIGN_SYNC_STORAGE = new InjectionToken<{ getItem(key: string): string | null; setItem(key: string, value: string): void } | null>(
  'Campaign reconciliation storage', {
    providedIn: 'root', factory: /** Policy denial is represented by null, never thrown into rendering. */ () => {
      try { return globalThis.localStorage ?? null; } catch { return null; }
    },
  });

/** One tracked operation as persisted: identity, newest version seen and its status. */
export interface TrackedOperation {
  readonly operationId: string;
  readonly commandType: string;
  readonly status: OperationStatus;
  readonly operationVersion: number;
  /** In-memory only: the receipt is re-read from the operation endpoint after a reload. */
  readonly receipt?: PublicReceipt | null;
  readonly reason?: string | null;
}

/** Persisted reconciliation record for one scope. */
interface SyncRecord { readonly cursor: string | null; readonly operations: readonly TrackedOperation[] }

/** Maximum operations remembered per campaign; terminal ones are evicted first. */
export const MAX_TRACKED = 50;
/** Saved operations whose receipt is re-read after a reload; older receipts stay unloaded to bound requests. */
const RECEIPT_REREADS = 10;

/** Fetch bounded JSON/SSE from the single origin and map failures to {@link CampaignProtocolError}. */
@Injectable({ providedIn: 'root' })
export class CampaignTransport {
  private readonly fetcher = inject(LIBRARY_FETCH);
  private readonly library = inject(SavedLibraryClient);

  /** Read and validate the campaign state for one scope. */
  async state(scope: CampaignScope, signal: AbortSignal): Promise<CampaignView> {
    const body = await this.json(`/api/v1/campaigns/${scope.campaignId}/state?adventureId=${scope.adventureId}`, signal);
    return parseCampaignView(body, scope.adventureId, scope.campaignId);
  }

  /** Read and validate the resume card of one scope (P09.5); reading it never advances time. */
  async resume(scope: CampaignScope, signal: AbortSignal): Promise<ResumeCard> {
    const body = await this.json(`/api/v1/campaigns/${scope.campaignId}/resume?adventureId=${scope.adventureId}`, signal);
    return parseResumeCard(body, scope.adventureId, scope.campaignId);
  }

  /**
   * Read and validate the player panels and journal of one scope (P10.5). `before` pages the journal
   * backwards from a previous page's cursor; reading never advances time or writes anything.
   */
  async panels(scope: CampaignScope, signal: AbortSignal, before: number | null = null): Promise<PlayPanels> {
    const cursor = before === null ? '' : `&before=${before}`;
    const body = await this.json(`/api/v1/campaigns/${scope.campaignId}/panels?adventureId=${scope.adventureId}${cursor}`, signal);
    return parsePlayPanels(body, scope.adventureId, scope.campaignId);
  }

  /** Read one operation's current status by identity; the safe query after a reload or a lost reply. */
  async operation(scope: CampaignScope, operationId: string, signal: AbortSignal): Promise<OperationView> {
    const body = await this.json(`/api/v1/campaigns/${scope.campaignId}/operations/${operationId}?adventureId=${scope.adventureId}`, signal);
    const view = parseOperation(body, scope.campaignId);
    if (view.operationId !== operationId) throw new CampaignProtocolError('invalid', 'Foreign operation');
    return view;
  }

  /**
   * Run one bounded stream long poll after `after` (null starts at the head).
   *
   * Validated events are handed to `onChunk` as each network chunk completes, so a held
   * connection delivers messages immediately rather than when the server ends the response.
   * `onChunk` is called once with no events when the response opens. It returns `'stop'` to cancel the rest of this response (used when the server
   * resets the stream). The call resolves when the response ends, is stopped or is aborted;
   * an abort rejects with `aborted`.
   */
  async stream(scope: CampaignScope, after: string | null, signal: AbortSignal,
    onChunk: (events: StreamEvent[]) => 'stop' | void): Promise<void> {
    const query = new URLSearchParams({ adventureId: scope.adventureId });
    if (after !== null) query.set('after', after);
    const response = await this.get(`/api/v1/campaigns/${scope.campaignId}/stream?${query}`, 'text/event-stream', signal);
    if (response.status !== 200) throw await this.failure(response);
    if (!(response.headers.get('Content-Type') ?? '').toLowerCase().startsWith('text/event-stream') || !response.body) {
      throw new CampaignProtocolError('invalid', 'Stream reply is not an event stream');
    }
    const decoder = new FrameDecoder(scope.campaignId);
    const text = new TextDecoder();
    const reader = response.body.getReader();
    try {
      // An empty first delivery tells the caller the connection is open before any message exists.
      if (onChunk([]) === 'stop') { await reader.cancel(); return; }
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (onChunk(decoder.push(text.decode(value, { stream: true }))) === 'stop') { await reader.cancel(); break; }
      }
    } catch (error) {
      if (error instanceof CampaignProtocolError) throw error;
      throw new CampaignProtocolError(signal.aborted ? 'aborted' : 'unavailable', 'Stream interrupted');
    }
  }

  /** Read a JSON reply; non-200 statuses carry their stable error code. */
  private async json(path: string, signal: AbortSignal): Promise<unknown> {
    let response = await this.get(path, 'application/json', signal);
    if (response.status === 403) response = await this.get(path, 'application/json', signal, true);
    if (response.status !== 200) throw await this.failure(response);
    if (!(response.headers.get('Content-Type') ?? '').toLowerCase().startsWith('application/json')) {
      throw new CampaignProtocolError('invalid', 'Reply is not JSON');
    }
    try { return await response.json(); } catch { throw new CampaignProtocolError('invalid', 'Reply is not valid JSON'); }
  }

  /** Issue one admitted GET; a denial renews the session once through the shared bootstrap. */
  private async get(path: string, accept: string, signal: AbortSignal, renew = false): Promise<Response> {
    try {
      await this.library.ensureSession(renew);
      return await this.fetcher(path, { method: 'GET', credentials: 'same-origin', cache: 'no-store', signal, headers: { Accept: accept } });
    } catch {
      throw new CampaignProtocolError(signal.aborted ? 'aborted' : 'unavailable', 'Campaign request failed');
    }
  }

  /** Build a typed failure carrying only an allowlist-shaped stable code from the body. */
  private async failure(response: Response): Promise<CampaignProtocolError> {
    const body = await response.json().catch(/** Unreadable error bodies carry no code. */ () => undefined) as { code?: unknown } | undefined;
    const code = typeof body?.code === 'string' && /^[A-Z][A-Z_]{2,63}$/.test(body.code) ? body.code : null;
    return new CampaignProtocolError('api', `Campaign status ${response.status}`, response.status, code);
  }
}

/** Outcome of one reconcile pass. */
export interface ReconcileResult {
  /** True when the server reset the stream and the saved cursor was replaced by the current head. */
  readonly reset: string | null;
  /** Events newly applied (duplicates excluded). */
  readonly applied: number;
  /** Events skipped as already delivered. */
  readonly duplicates: number;
  /**
   * Highest world revision named by an applied message or a reset, or null when neither occurred.
   * A value above the displayed state's revision means that state is stale and must be re-read.
   */
  readonly stateRevision: number | null;
}

/** Own per-scope reconciliation records, durable across language reloads; see the module comment. */
@Injectable({ providedIn: 'root' })
export class CampaignSession {
  private readonly storage = inject(CAMPAIGN_SYNC_STORAGE);
  private readonly transport = inject(CampaignTransport);
  private readonly records = new Map<string, { cursor: string | null; operations: Map<string, TrackedOperation> }>();
  /** Bumps on every change so views recompute; carries no data. */
  readonly revision = signal(0);
  /** `saved` while the last write verified; `unavailable` when storage failed, which blocks language reload. */
  readonly persistence = signal<'saved' | 'unavailable'>('saved');

  /** Storage key for one scope; JSON encoding keeps punctuation in IDs from merging scopes. */
  private key(scope: CampaignScope): string {
    return 'engine.campaign-sync.v1:' + JSON.stringify([scope.adventureId, scope.campaignId]);
  }

  /** Load (once) and return the scope's record, merging any persisted bytes. */
  private record(scope: CampaignScope): { cursor: string | null; operations: Map<string, TrackedOperation> } {
    const key = this.key(scope);
    let current = this.records.get(key);
    if (!current) {
      current = { cursor: null, operations: new Map() };
      this.records.set(key, current);
      this.merge(current, this.read(key));
    }
    return current;
  }

  /** Parse persisted bytes; corrupt, oversized or foreign data yields an empty record and is overwritten later. */
  private read(key: string): SyncRecord {
    const empty: SyncRecord = { cursor: null, operations: [] };
    try {
      const raw = this.storage?.getItem(key) ?? null;
      if (raw === null || raw.length > 40_000) return empty;
      const value = JSON.parse(raw) as Record<string, unknown>;
      if (value['version'] !== 1 || !Array.isArray(value['operations']) || value['operations'].length > MAX_TRACKED) return empty;
      const cursor = value['cursor'];
      if (cursor !== null && !parseCursor(cursor)) return empty;
      const operations: TrackedOperation[] = [];
      for (const entry of value['operations'] as Record<string, unknown>[]) {
        const id = entry['operationId'];
        const status = entry['status'];
        const version = entry['operationVersion'];
        const type = entry['commandType'];
        if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/.test(id) || !OPERATION_STATUSES.includes(status as OperationStatus) ||
          typeof version !== 'number' || !Number.isSafeInteger(version) || version < 0 ||
          typeof type !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(type)) return empty;
        operations.push({ operationId: id, commandType: type, status: status as OperationStatus, operationVersion: version });
      }
      return { cursor: cursor as string | null, operations };
    } catch { return empty; }
  }

  /**
   * Merge an incoming record into memory, keeping the newer cursor and higher operation versions.
   *
   * @param adoptCursor true when the in-memory cursor was just taken from a server reset: the
   *   server has declared the stored position unknown or expired, so a larger stored value is
   *   exactly the stale position being replaced and must not win the comparison
   */
  private merge(into: { cursor: string | null; operations: Map<string, TrackedOperation> }, from: SyncRecord, adoptCursor = false): void {
    const a = parseCursor(into.cursor);
    const b = parseCursor(from.cursor);
    if (!adoptCursor && b && (!a || (compareCursor(b, a) ?? (b.generation > a.generation ? 1 : -1)) > 0)) into.cursor = from.cursor;
    for (const operation of from.operations) this.upsert(into.operations, operation);
  }

  /** Insert or advance one operation; an equal or lower version is a duplicate and returns false. */
  private upsert(operations: Map<string, TrackedOperation>, next: TrackedOperation): boolean {
    const known = operations.get(next.operationId);
    // An equal version is a duplicate, except that it may supply the receipt a reload dropped.
    const receiptOnly = known !== undefined && known.operationVersion === next.operationVersion && !!next.receipt && !known.receipt;
    if (known && known.operationVersion >= next.operationVersion && !receiptOnly) return false;
    operations.delete(next.operationId);
    operations.set(next.operationId, { ...next, receipt: next.receipt ?? known?.receipt ?? null });
    while (operations.size > MAX_TRACKED) {
      const victim = [...operations.values()].find(/** Prefer evicting finished work over pending work. */ (entry) => !isPending(entry.status)) ??
        operations.values().next().value!;
      operations.delete(victim.operationId);
    }
    return true;
  }

  /** Serialize the persisted subset (no receipts, text or drafts). */
  private serialize(current: { cursor: string | null; operations: Map<string, TrackedOperation> }): string {
    return JSON.stringify({ version: 1, cursor: current.cursor, operations: [...current.operations.values()].map(
      /** Persist identity, version and status only. */ (o) => ({ operationId: o.operationId, commandType: o.commandType, status: o.status, operationVersion: o.operationVersion })) });
  }

  /**
   * Persist the scope's record after merging whatever another tab wrote, then re-read the exact bytes.
   *
   * @param adoptCursor true after a stream reset; the server's head replaces the stored cursor
   *   (see {@link merge}) while operations still merge
   * @returns true only when the stored bytes equal the in-memory record; false on any storage failure
   */
  private persist(scope: CampaignScope, adoptCursor = false): boolean {
    const current = this.record(scope);
    const key = this.key(scope);
    try {
      if (!this.storage) throw new Error('Storage unavailable');
      // Another tab may have advanced the cursor; never regress it by blindly overwriting,
      // except when a reset says the stored position itself is no longer valid.
      this.merge(current, this.read(key), adoptCursor);
      const bytes = this.serialize(current);
      this.storage.setItem(key, bytes);
      if (this.storage.getItem(key) !== bytes) throw new Error('Storage verification failed');
      this.persistence.set('saved');
      return true;
    } catch {
      this.persistence.set('unavailable');
      return false;
    }
  }

  /** Last delivered stream identity for a scope, or null before any delivery. */
  cursor(scope: CampaignScope): string | null {
    this.revision();
    return this.record(scope).cursor;
  }

  /** Tracked operations, oldest first; pending ones are those {@link isPending} reports. */
  operations(scope: CampaignScope): readonly TrackedOperation[] {
    this.revision();
    return [...this.record(scope).operations.values()];
  }

  /**
   * Verify that this scope can survive a reload: the record is merged, written and read back exactly.
   * Pending operations are acceptable because they are re-queried by identity after the reload.
   */
  verified(scope: CampaignScope): boolean {
    return this.persist(scope);
  }

  /** Record an operation view obtained from a status query; returns false for a duplicate/older version. */
  applyOperation(scope: CampaignScope, view: OperationView): boolean {
    const changed = this.upsert(this.record(scope).operations, {
      operationId: view.operationId, commandType: view.commandType, status: view.status,
      operationVersion: view.operationVersion, receipt: view.receipt,
    });
    if (changed) { this.persist(scope); this.revision.update((n) => n + 1); }
    return changed;
  }

  /**
   * Apply one stream event.
   *
   * Control events never touch operations. A message is applied only when its identity is
   * strictly after the saved cursor in the same generation; anything else is a duplicate.
   * The cursor moves only after the message is applied, so a crash replays instead of skipping.
   *
   * @returns 'applied', 'duplicate' or 'foreign-generation' (the caller must treat the stream as reset)
   */
  applyEvent(scope: CampaignScope, event: StreamEvent): 'applied' | 'duplicate' | 'foreign-generation' {
    const current = this.record(scope);
    const saved = parseCursor(current.cursor);
    if (event.type === 'stream.ready' || event.type === 'stream.reset') return 'applied';
    if (saved) {
      const order = compareCursor(event.cursor, saved);
      if (order === undefined) return 'foreign-generation';
      if (order <= 0) return 'duplicate';
    }
    if (event.operationId !== null && event.status !== null && event.operationVersion !== null && event.commandType !== null) {
      this.upsert(current.operations, {
        operationId: event.operationId, commandType: event.commandType, status: event.status,
        operationVersion: event.operationVersion, receipt: event.receipt, reason: event.reason,
      });
    }
    current.cursor = event.id;
    this.persist(scope);
    this.revision.update((n) => n + 1);
    return 'applied';
  }

  /**
   * Re-query unfinished operations by identity, the authoritative state after a load, reload
   * or reconnect. Recent saved operations whose in-memory receipt was dropped by a reload are
   * re-read too (bounded). Network failures propagate; the saved record stays valid.
   */
  async requery(scope: CampaignScope, signal: AbortSignal): Promise<void> {
    const all = this.operations(scope);
    // Unfinished work can still change; recent saved operations lost their in-memory receipt in the reload.
    const missingReceipts = all.filter(/** Delivered and narration-pending operations always have a receipt on the server. */ (entry) =>
      !entry.receipt && ['DELIVERED', 'COMMITTED_NARRATION_PENDING'].includes(entry.status)).slice(-RECEIPT_REREADS);
    const wanted = new Set([...all.filter(/** Pending operations. */ (entry) => isPending(entry.status)), ...missingReceipts]);
    for (const operation of wanted) this.applyOperation(scope, await this.transport.operation(scope, operation.operationId, signal));
  }

  /**
   * Read one bounded stream response after the saved cursor, applying messages as they arrive.
   *
   * A reset adopts the server's head as the new cursor and stops reading; the caller must then
   * {@link requery} so no message class is silently skipped. A message from another generation
   * is reported as `GENERATION_CHANGED` without moving the cursor (the next poll is answered
   * with a reset). `onActivity` fires after every network chunk, which proves a live connection.
   * The result names the highest revision an applied message or reset carried, so a caller can
   * re-read a state projection that a commit made stale; duplicates never raise it.
   */
  async poll(scope: CampaignScope, signal: AbortSignal, onActivity: () => void = () => undefined): Promise<ReconcileResult> {
    let reset: string | null = null;
    let applied = 0;
    let duplicates = 0;
    let stateRevision: number | null = null;
    await this.transport.stream(scope, this.record(scope).cursor, signal, (events) => {
      onActivity();
      for (const event of events) {
        if (event.type === 'stream.reset') {
          reset = event.reason;
          stateRevision = Math.max(stateRevision ?? 0, event.stateRevision);
          this.record(scope).cursor = event.id;
          // The reset declares the stored position invalid (an unknown cursor can be ahead of the head);
          // merging that value back would make every later poll reset again.
          this.persist(scope, true);
          this.revision.update((n) => n + 1);
          return 'stop';
        }
        if (event.type === 'stream.ready') {
          // First connection: start after the head so history already reflected in state is not replayed.
          if (this.record(scope).cursor === null) { this.record(scope).cursor = event.id; this.persist(scope); }
          continue;
        }
        const outcome = this.applyEvent(scope, event);
        if (outcome === 'applied') { applied++; stateRevision = Math.max(stateRevision ?? 0, event.stateRevision); }
        else if (outcome === 'duplicate') duplicates++;
        else { reset = 'GENERATION_CHANGED'; return 'stop'; }
      }
      return undefined;
    });
    return { reset, applied, duplicates, stateRevision };
  }

  /** Requery pending work then poll once, requerying again after a reset; the combined load/reload step. */
  async reconcile(scope: CampaignScope, signal: AbortSignal): Promise<ReconcileResult> {
    await this.requery(scope, signal);
    const result = await this.poll(scope, signal);
    if (result.reset) await this.requery(scope, signal);
    return result;
  }
}
