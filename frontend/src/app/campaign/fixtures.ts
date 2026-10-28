/** Shared simulated campaign server for specs: canned state/operation/stream replies with failure controls. */
import { CAMPAIGN_SYNC_STORAGE } from './campaign-session';

export const ADVENTURE = '00000000-0000-4000-8000-00000000000a';
export const CAMPAIGN = '00000000-0000-4000-8000-0000000000a1';
export const OTHER_CAMPAIGN = '00000000-0000-4000-8000-0000000000a2';
export const OP1 = '00000000-0000-4000-8000-0000000000c1';
export const OP2 = '00000000-0000-4000-8000-0000000000c2';
export const SCOPE = { adventureId: ADVENTURE, campaignId: CAMPAIGN };

/** In-memory storage with configurable denial; mirrors browser failure behavior. */
export class MemoryStore {
  readonly values = new Map<string, string>();
  denied = false;
  getItem(key: string): string | null { if (this.denied) throw new Error('Denied'); return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { if (this.denied) throw new Error('Denied'); this.values.set(key, value); }
}

export const syncProvider = (store: MemoryStore) => ({ provide: CAMPAIGN_SYNC_STORAGE, useValue: store });

/** JSON response with the server's content type. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Canonical public receipt projection as the backend writes it. */
export function receipt(revision = 2) {
  return { stateRevision: revision, firstEventSequence: 3, lastEventSequence: 4, commandType: 'USE_ABILITY', elapsedSeconds: 1500,
    turnAdvanced: true, resources: [{ resourceKey: 'vigor', before: 12000, after: 11000 }],
    calculations: [{ path: '/effects/0', kind: 'CHECK', value: true, drawOrdinals: [0] }],
    randomDraws: [{ ordinal: 0, sides: 20, value: 17 }] };
}

/** Operation body as the status endpoint publishes it. */
export function operation(id: string, status: string, version: number, withReceipt = false) {
  return { operationId: id, campaignId: CAMPAIGN, commandType: 'USE_ABILITY', status, baseRevision: 1,
    committedRevision: withReceipt ? 2 : null, operationVersion: version, replayed: false, createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:01Z', receipt: withReceipt ? receipt() : null, query: '/q', stream: '/s' };
}

/** One SSE frame for an operation message. */
export function frame(id: string, type: string, data: Record<string, unknown>): string {
  return `id: ${id}\nevent: ${type}\ndata: ${JSON.stringify({ campaignId: CAMPAIGN, stateRevision: 2, ...data })}\n\n`;
}

/** Campaign state body; `fallback` marks a source-language starting text. */
export function state(options: { fallback?: boolean; locale?: string; situation?: boolean } = {}) {
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: OP2, stateRevision: 2 },
    campaign: { campaignId: CAMPAIGN, adventureId: ADVENTURE, contentRevisionId: OP2, title: 'Mara', mode: 'EXPLORATION', stateRevision: 2,
      gameTurn: 3, worldTimeSeconds: 0, eventSequence: 4, updatedAt: '2026-10-28T00:00:00Z', pendingDecisionAvailable: true },
    player: {}, resources: [], inventory: [], abilities: [], knownFacts: [], quests: [],
    startingSituation: options.situation === false ? null : { text: 'Una lanterna brilla.', locale: options.locale ?? 'it', fallback: options.fallback ?? true },
    pendingDecision: null };
}

/** Resume card body (P09.5); `paused` adds a missing-content diagnosis, `awaiting` one owed narration. */
export function resumeCard(options: { paused?: boolean; awaiting?: boolean; decision?: string } = {}) {
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: OP2, stateRevision: 2 },
    title: 'Mara', mode: 'EXPLORATION', gameTurn: 3, worldTimeSeconds: 0,
    location: { id: 'trailhead', name: 'Lantern Shelter', nameLocale: 'en', nameFallback: false },
    presentActors: [{ actorId: OP1, name: 'Warden' }],
    resources: [{ key: 'vigor', minimum: 0, current: 11000, maximum: 12000 }],
    encounter: null, pendingDecision: options.decision ?? 'FREE_INPUT',
    awaitingNarrationTotal: options.awaiting ? 1 : 0,
    awaitingNarration: options.awaiting ? [{ operationId: OP1, commandType: 'USE_ABILITY', status: 'COMMITTED_NARRATION_PENDING',
      committedRevision: 2, receipt: receipt(), narration: { mode: 'RECEIPT', reason: 'NARRATION_UNAVAILABLE', retriesRemaining: 3 } }] : [],
    dependencies: options.paused
      ? { status: 'MISSING', mutationPaused: true, issues: [{ code: 'DEPENDENCY_MISSING', packId: 'sample:lanterns', packVersion: '1.0.0' }] }
      : { status: 'AVAILABLE', mutationPaused: false, issues: [] },
    continuity: { checkpoint: 'VALID', publicSummary: 'NONE' } };
}

/** Scriptable fake server for the campaign endpoints. */
export class CampaignServer {
  readonly calls: string[] = [];
  streams: string[][] = [];
  operations = new Map<string, ReturnType<typeof operation>>();
  stateBody: unknown = state();
  resumeBody: unknown = resumeCard();
  failStream = 0;
  statusCode = 200;
  errorCode = '';

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    this.calls.push(url);
    if (url === '/api/v1/session') return Promise.resolve(json({ csrfToken: 'token' }));
    // Saved-library endpoints the surrounding screen needs to select this playthrough.
    if (url === `/api/v1/adventures/${ADVENTURE}`) {
      return Promise.resolve(json({ adventureId: ADVENTURE, title: 'The Lantern', genre: 'Fantasy', sourceLocale: 'it', metadataRevision: 0,
        createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z' }));
    }
    if (url.startsWith('/api/v1/campaigns?')) return Promise.resolve(json({ campaigns: [(state() as { campaign: unknown }).campaign], nextAfter: null }));
    if (this.errorCode) return Promise.resolve(json({ code: this.errorCode }, this.statusCode));
    if (url.includes('/state?')) return Promise.resolve(json(this.stateBody));
    if (url.includes('/resume?')) return Promise.resolve(json(this.resumeBody));
    const op = /\/operations\/([0-9a-f-]{36})\?/.exec(url);
    if (op) {
      const view = this.operations.get(op[1]);
      return Promise.resolve(view ? json(view) : json({ code: 'COMMAND_SCOPE_UNAVAILABLE' }, 404));
    }
    if (url.includes('/stream?')) {
      if (this.failStream > 0) { this.failStream--; return Promise.reject(new TypeError('network')); }
      const frames = this.streams.shift();
      // An exhausted script behaves like a held long poll that ended without messages.
      const encoder = new TextEncoder();
      const body = new ReadableStream<Uint8Array>({ start(controller) { for (const chunk of frames ?? []) controller.enqueue(encoder.encode(chunk)); controller.close(); } });
      return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } }));
    }
    return Promise.resolve(json({ code: 'COMMAND_SCOPE_UNAVAILABLE' }, 404));
  };
}
