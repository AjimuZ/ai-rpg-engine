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

/**
 * Panels body (P10.5) shaped as the backend writes it: a clock at day 2, one attribute changed by a
 * condition, worn equipment, an active quest with a satisfied and an open objective, a rumor, a promise
 * and two journal events. `contentAvailable: false` removes every pack name.
 */
export function panelsBody(options: { contentAvailable?: boolean; nextBefore?: number | null; fallback?: boolean } = {}) {
  const named = options.contentAvailable !== false;
  const text = (value: string, locale = 'en') => (named ? { text: value, locale, fallback: options.fallback ?? false } : null);
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: OP2, stateRevision: 2 }, locale: 'en',
    contentAvailable: named, clock: { seconds: 90_000 + 3_660, day: 2, hour: 2, minute: 1, second: 0 },
    character: { actorId: OP1, name: 'Mara', role: 'Surveyor', location: { id: 'trailhead', name: text('Lantern Shelter') },
      attributes: [{ key: 'awareness', name: text('Awareness'), base: 3, effective: 1 }, { key: 'might', name: text('Might'), base: 2, effective: 2 }],
      skills: [{ key: 'fieldwork', name: text('Trailcraft'), rank: 2 }], derived: [{ key: 'guard', value: 5 }] },
    resources: [{ key: 'vigor', name: text('Vigor'), minimum: 0, current: 6, maximum: 8 }],
    conditions: [{ id: 'dazed', name: text('Dazed'), stacks: 1, remainingRounds: 3 }],
    abilities: [{ id: 'steady', name: text('Catch Breath'), rank: 1 }],
    inventory: [{ itemId: OP1, definitionId: 'sword', name: text('Sword'), quantity: 1, rarity: 'ordinary', rarityName: text('Ordinary'), wornIn: 'main-hand' },
      { itemId: OP2, definitionId: 'coin', name: text('Coin'), quantity: 10, rarity: null, rarityName: null, wornIn: null }],
    equipment: [{ itemId: OP1, definitionId: 'sword', name: text('Sword'), slot: 'main-hand', hands: 1 }],
    quests: [{ id: 'survey', name: text('Find the Waystone'), summary: text('Visit the grove.'), status: 'active', updatedRevision: 1,
      objectives: [{ id: 'reach', description: text('Reach the grove.'), optional: false, satisfied: true },
        { id: 'provisions', description: text('Carry two potions.'), optional: true, satisfied: false }], outcome: null },
    { id: 'gate', name: text('Light the Gate'), summary: text('Bring oil to the gate lamp.'), status: 'completed', updatedRevision: 2,
      objectives: [], outcome: { id: 'lit', kind: 'success', description: null, rewards: [{ itemId: 'coin', name: text('Coin'), quantity: 5 }] } }],
    knowledge: { discoveriesTotal: 1, discoveries: [{ claimId: OP1, stance: 'RUMORED', acquisition: 'TOLD', statement: 'The warden hides a key.',
      statementLocale: 'en', learnedRevision: 1, learnedWorldTime: 60 }],
      promisesTotal: 1, promises: [{ commitmentId: OP2, kind: 'PROMISE', direction: 'YOU_OWE', debtor: 'Mara', creditor: 'Warden',
        terms: 'Guard the gate.', termsLocale: 'en', dueWorldTime: 100_000, overdue: false, madeRevision: 1 }] },
    journal: { entries: [{ sequence: 4, type: 'ACTOR_MOVED', stateRevision: 2, clock: { seconds: 90_000, day: 2, hour: 1, minute: 0, second: 0 },
      statement: 'Mara moved from grove to trailhead.' },
    { sequence: 3, type: 'TIME_PASSED', stateRevision: 1, clock: { seconds: 60, day: 1, hour: 0, minute: 1, second: 0 },
      statement: 'Waiting: 60 seconds pass.' }], nextBefore: options.nextBefore === undefined ? null : options.nextBefore } };
}

/** Scriptable fake server for the campaign endpoints. */
export class CampaignServer {
  readonly calls: string[] = [];
  streams: string[][] = [];
  operations = new Map<string, ReturnType<typeof operation>>();
  stateBody: unknown = state();
  resumeBody: unknown = resumeCard();
  panelsBody: unknown = panelsBody();
  /** Journal pages served for `before=` requests, keyed by cursor. */
  olderPages = new Map<number, unknown>();
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
    if (url.includes('/panels?')) {
      const before = /[?&]before=(\d+)/.exec(url);
      return Promise.resolve(json(before ? this.olderPages.get(Number(before[1])) ?? { code: 'CAMPAIGN_SCOPE_UNAVAILABLE' } : this.panelsBody));
    }
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
