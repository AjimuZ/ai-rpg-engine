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

/** The actor who speaks in {@link narratedOperation}; the same public identity the portrait lookup uses. */
export const SPEAKER = '00000000-0000-4000-8000-0000000000d1';

/**
 * Operation body with published narration (P08.5 `presentation`): a narrator passage, one passage spoken by {@link SPEAKER}
 * and, unless `suggestions` is false, three quick replies of the three kinds. It is the scripted narration source of the specs.
 */
export function narratedOperation(id: string, options: { version?: number; revision?: number; suggestions?: boolean; speech?: boolean } = {}) {
  const revision = options.revision ?? 2;
  const base = operation(id, 'DELIVERED', options.version ?? 6, true);
  const passages: unknown[] = [{ messageId: `${id}:p1`, kind: 'NARRATION', speaker: null, text: 'The grove is quiet.', locale: 'en' }];
  if (options.speech !== false) passages.push({ messageId: `${id}:p2`, kind: 'CHARACTER_SPEECH', speaker: { actorId: SPEAKER, name: 'Trail keeper' }, text: 'Will you come with me?', locale: 'en' });
  return { ...base, committedRevision: revision, receipt: { ...receipt(revision) },
    presentation: { mode: 'NARRATED', reason: null, retriesRemaining: 0, narration: { locale: 'en', repaired: false, passages,
      suggestions: options.suggestions === false ? [] : [
        { suggestionId: `${id}:s1`, kind: 'REPLY', label: 'Agree', draftText: 'I will come with you.', actionId: null },
        { suggestionId: `${id}:s2`, kind: 'QUESTION', label: 'Ask about the grove', draftText: 'What do you know about the grove?', actionId: null },
        { suggestionId: `${id}:s3`, kind: 'ACTION', label: 'Look from here', draftText: 'I look at the trail from here.', actionId: null }] } } };
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

/**
 * Combat projection body (P12.1) as the backend writes it: round 2 of a fight at the player's own decision, two zones and a
 * blocked tower, a hostile sentry whose defenses the player assessed in round 1, an ally, and one candidate of each family
 * including disabled ones. `verdict` shows the fight decided and waiting for settlement; `none` is the body outside a fight, which
 * with `offers` names the two encounters of {@link offeredEncounters} (P13.2-B). A fight's body always has empty offers.
 */
export function encounterBody(options: { verdict?: boolean; none?: boolean; offer?: boolean; offers?: boolean } = {}) {
  const scope = { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: OP2, stateRevision: 2 };
  if (options.none) return { scope, mode: 'EXPLORATION', encounter: null, offers: options.offers ? offeredEncounters() : [] };
  const decided = options.verdict === true;
  return { scope, mode: 'ENCOUNTER', offers: [], encounter: {
    encounterId: OP2, templateId: 'gate-fight', phase: decided ? 'OUTCOME_PENDING' : 'AWAITING_ACTION', round: 2, roundCap: 8,
    acting: decided ? null : 'player', actionPoints: decided ? null : 2, actionPointsPerActivation: 3, playerDecision: !decided,
    initiative: [{ rank: 0, participantId: 'player', initiative: 14 }, { rank: 1, participantId: 'sentry', initiative: 9 }, { rank: 2, participantId: 'scout', initiative: 7 }],
    zones: [{ id: 'gate', cover: 'none', obscured: false, capacity: 0 }, { id: 'yard', cover: 'partial', obscured: true, capacity: 2 }, { id: 'tower', cover: 'full', obscured: false, capacity: 1 }],
    edges: [{ from: 'gate', to: 'yard', difficult: false, blocked: false, cost: 1 }, { from: 'yard', to: 'gate', difficult: true, blocked: false, cost: 2 },
      { from: 'yard', to: 'tower', difficult: false, blocked: true, cost: null }, { from: 'tower', to: 'yard', difficult: false, blocked: true, cost: null }],
    escapeZones: ['gate'],
    participants: [
      { participantId: 'player', side: 'player', zone: 'gate', status: 'ACTIVE', effects: [], known: null },
      { participantId: 'sentry', side: 'hostile', zone: 'yard', status: 'ACTIVE', effects: [{ effectId: 'dazed', stacks: 1 }],
        known: { health: 6, healthMax: 10, guard: 3, resistance: 1, armor: 2, round: 1, stateRevision: 2 } },
      { participantId: 'scout', side: 'ally', zone: 'gate', status: 'ACTIVE', effects: [], known: null }],
    player: { health: 14, healthMax: 20, focus: 3, focusMax: 5, guard: 4, resistance: 2, armor: 1, guarding: false, reactionsLeft: 1 },
    objectives: [{ objectiveId: 'clear', kind: 'defeatHostiles', zone: null, participantId: null, rounds: null, status: decided ? 'met' : 'pending' },
      { objectiveId: 'hold', kind: 'survive', zone: null, participantId: null, rounds: 8, status: 'pending' },
      { objectiveId: 'cover', kind: 'protect', zone: null, participantId: 'scout', rounds: null, status: 'pending' }],
    options: decided ? {} : {
      moves: [{ zone: 'yard', cost: 1 }], speech: { remaining: 1, recipients: ['sentry'] },
      actions: [{ actionId: 'plead', kind: 'social', cost: 1, targets: ['sentry'], check: { attribute: 'presence', skill: null, rank: 1 },
        terms: { termsId: 'toll', kind: 'pay', cost: 1 } }],
      strikes: [{ attackId: 'blade', cost: 2, targets: ['sentry'], disabled: null }, { attackId: 'bow', cost: 2, targets: [], disabled: 'NO_AMMUNITION' }],
      guard: { cost: 1, available: true }, useItem: { cost: 1, available: false },
      abilities: [{ abilityId: 'rally', cost: 1, focus: 2, targets: ['player'], maxTargets: 1, disabled: null },
        { abilityId: 'surge', cost: 3, focus: 9, targets: [], maxTargets: 1, disabled: 'FOCUS' }],
      reactions: [{ reactionId: 'parry', policy: { mode: 'healthAtMost', value: 50 } }], reactionsLeft: 1,
      ready: { cost: 1, available: true }, assess: { cost: 1, targets: ['sentry'] }, withdraw: [{ zone: 'gate', cost: 1 }], endActivation: true,
      ...(options.offer ? { offer: { offerId: 'offer-1', actionId: 'plead', recipientId: 'sentry', response: 'OFFERED',
        terms: { termsId: 'toll', kind: 'pay', cost: 1 }, canAccept: true } } : {}) },
    outcome: decided ? { outcome: 'VICTORY', objectiveId: null, objectives: [{ objectiveId: 'clear', status: 'met' }] } : null } };
}

/**
 * Offers outside a fight as the backend writes them (P13.2-B): an ambush that may open with an attack on the raider, with an ally,
 * an escape zone and two stakes, and a parley that cannot be opened by an attack and has neither escape zones nor objectives.
 */
export function offeredEncounters() {
  return [
    { templateId: 'trail-ambush', playerZone: 'entry',
      participants: [{ participantId: 'raider', side: 'hostile', zone: 'yard' }, { participantId: 'keeper', side: 'ally', zone: 'entry' }],
      escapeZones: ['escape-route'],
      objectives: [{ objectiveId: 'drive-off', kind: 'defeatHostiles', zone: null, participantId: null, rounds: null },
        { objectiveId: 'hold', kind: 'survive', zone: null, participantId: null, rounds: 3 }],
      openingPolicy: 'initiative', openingTargets: ['raider'] },
    { templateId: 'toll-parley', playerZone: 'gate', participants: [{ participantId: 'smuggler', side: 'hostile', zone: 'booth' }],
      escapeZones: [], objectives: [], openingPolicy: 'forbidden', openingTargets: [] },
  ];
}

/** Resume card body (P09.5); `paused` adds a missing-content diagnosis, `awaiting` one owed narration, `fight` an active encounter. */
export function resumeCard(options: { paused?: boolean; awaiting?: boolean; decision?: string; fight?: boolean } = {}) {
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: OP2, stateRevision: 2 },
    title: 'Mara', mode: 'EXPLORATION', gameTurn: 3, worldTimeSeconds: 0,
    location: { id: 'trailhead', name: 'Lantern Shelter', nameLocale: 'en', nameFallback: false },
    presentActors: [{ actorId: OP1, name: 'Warden' }],
    resources: [{ key: 'vigor', minimum: 0, current: 11000, maximum: 12000 }],
    encounter: options.fight ? { encounterId: OP2, templateId: 'gate-fight', phase: 'AWAITING_ACTION', round: 2, acting: 'player', actionPoints: 2,
      playerDecision: true, speechRemaining: 1, offer: null, outcome: null } : null,
    pendingDecision: options.decision ?? 'FREE_INPUT',
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
  operations = new Map<string, unknown>();
  stateBody: unknown = state();
  resumeBody: unknown = resumeCard();
  panelsBody: unknown = panelsBody();
  encounterBody: unknown = encounterBody({ none: true });
  /** Artwork listing (P17.1-B); empty by default, as for a campaign without registered images. */
  artworkBody: unknown = { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, artwork: [] };
  /** Music listing (P17.2-D); no policy and no track by default, as for a pack that declares no music. */
  musicBody: unknown = { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, policy: null, tracks: [] };
  /** Journal pages served for `before=` requests, keyed by cursor. */
  olderPages = new Map<number, unknown>();
  failStream = 0;
  statusCode = 200;
  errorCode = '';
  /** Commands received (P12.2-A) with their CSRF header and decoded body, in order. */
  readonly commands: { readonly csrf: string | undefined; readonly body: Record<string, any> }[] = [];
  /** Reply to a command; the default is a committed operation. Throwing a TypeError models a lost reply. */
  commandReply: () => Response = () => json(operation(OP1, 'COMMITTED', 1, true), 202);
  /** While set, state reads wait for it, so a test can render the page between a commit and its re-read. */
  stateHold: Promise<void> | null = null;
  /**
   * Runtime artwork listing (P17.6-C), called on every read so a test can change it between polls; null answers 404, as for a
   * server without the route, which leaves every earlier screen test unchanged.
   */
  imageRequestsBody: (() => unknown) | null = null;
  /** Image requests received (P17.6-C) with their CSRF header and decoded body, in order. */
  readonly imageAsks: { readonly csrf: string | undefined; readonly body: Record<string, any> }[] = [];
  /** Reply to an image request; throwing a TypeError models a lost reply. */
  imageReply: () => Response = () => json({ code: 'ARTWORK_SUBJECT_UNAVAILABLE' }, 404);

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    this.calls.push(url);
    if (url === '/api/v1/session') return Promise.resolve(json({ csrfToken: 'token' }));
    if (init?.method === 'POST' && url.includes('/commands?')) {
      this.commands.push({ csrf: (init.headers as Record<string, string> | undefined)?.['X-CSRF-Token'], body: JSON.parse(String(init.body)) });
      try { return Promise.resolve(this.commandReply()); } catch (error) { return Promise.reject(error); }
    }
    // Saved-library endpoints the surrounding screen needs to select this playthrough.
    if (url === `/api/v1/adventures/${ADVENTURE}`) {
      return Promise.resolve(json({ adventureId: ADVENTURE, title: 'The Lantern', genre: 'Fantasy', sourceLocale: 'it', metadataRevision: 0,
        createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z' }));
    }
    if (url.startsWith('/api/v1/campaigns?')) return Promise.resolve(json({ campaigns: [(state() as { campaign: unknown }).campaign], nextAfter: null }));
    if (this.errorCode) return Promise.resolve(json({ code: this.errorCode }, this.statusCode));
    if (url.includes('/state?')) return (this.stateHold ?? Promise.resolve()).then(() => json(this.stateBody));
    if (url.includes('/resume?')) return Promise.resolve(json(this.resumeBody));
    if (url.includes('/encounter?')) return Promise.resolve(json(this.encounterBody));
    if (url.includes('/panels?')) {
      const before = /[?&]before=(\d+)/.exec(url);
      return Promise.resolve(json(before ? this.olderPages.get(Number(before[1])) ?? { code: 'CAMPAIGN_SCOPE_UNAVAILABLE' } : this.panelsBody));
    }
    if (url.includes('/artwork/requests?')) {
      if (init?.method === 'POST') {
        this.imageAsks.push({ csrf: (init.headers as Record<string, string> | undefined)?.['X-CSRF-Token'], body: JSON.parse(String(init.body)) });
        try { return Promise.resolve(this.imageReply()); } catch (error) { return Promise.reject(error); }
      }
      return Promise.resolve(this.imageRequestsBody === null ? json({ code: 'ARTWORK_SUBJECT_UNAVAILABLE' }, 404) : json(this.imageRequestsBody()));
    }
    if (url.includes('/artwork?')) return Promise.resolve(json(this.artworkBody));
    if (url.includes('/music?')) return Promise.resolve(json(this.musicBody));
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
