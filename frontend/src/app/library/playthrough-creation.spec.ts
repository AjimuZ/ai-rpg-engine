/**
 * Playthrough creation checks (P14.1-C): strict installed-pack and creation-reply parsing, no write before the
 * explicit submit, the exact creation body, a lost reply retried under the same request identity, a new identity
 * for changed choices, and the created playthrough opening in the saved adventure's live view. A simulated local
 * server replaces `fetch`.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { parseCreated } from '../campaign/campaign-session';
import { MemoryStore, syncProvider } from '../campaign/fixtures';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { LIBRARY_FETCH, parsePacks } from './saved-library-client';

const ADVENTURE = '00000000-0000-4000-8000-0000000000c1';
const OTHER = '00000000-0000-4000-8000-0000000000c2';
const REVISION = '00000000-0000-4000-8000-0000000000c3';
const CAMPAIGN = '00000000-0000-4000-8000-0000000000c4';
const ACTOR = '00000000-0000-4000-8000-0000000000c5';
const AT = '2026-11-20T10:00:00Z';

/** Build one installed-pack row as the server publishes it. */
function pack(adventureId = ADVENTURE, contentRevisionId = REVISION) {
  return { contentRevisionId, adventureId, packId: 'sample:lantern-ford', packVersion: '1.0.0', sourceLocale: 'en',
    admittedAt: AT, title: 'Lantern Ford', summary: 'A river crossing.', authors: ['Project contributors'], license: 'MIT' };
}

/** Return a JSON response with the server's content type. */
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** The bundled sample's entrypoint text (backend/src/main/resources/sample-adventure/adventure.json). */
const SITUATION = 'You reach the camp beside the ford at dusk. A raider waits by the crossing; you can face them, or look for another way to the far bank.';
const SCOPE = { adventureId: ADVENTURE, campaignId: CAMPAIGN, contentRevisionId: REVISION, stateRevision: 0 };
const english = (text: string) => ({ text, locale: 'en', fallback: false });

/**
 * Live-view reads of the new playthrough, shaped like a fresh Lantern Ford campaign: revision 0 at Ford Camp, the starting
 * situation waiting, full vigor, the crossing quest available, no fight and nothing journaled. These are the bodies
 * `FirstStartProvisioningTest` checks the real reads produce in their essentials (title, text, location, no pause).
 */
const LIVE = {
  state: { scope: SCOPE, campaign: { campaignId: CAMPAIGN, adventureId: ADVENTURE, contentRevisionId: REVISION, title: 'First watch',
    mode: 'EXPLORATION', stateRevision: 0, gameTurn: 0, worldTimeSeconds: 0, eventSequence: 0, updatedAt: AT, pendingDecisionAvailable: true },
  player: {}, resources: [], inventory: [], abilities: [], knownFacts: [], quests: [],
  startingSituation: { text: SITUATION, locale: 'en', fallback: false }, pendingDecision: null },
  resume: { scope: SCOPE, title: 'First watch', mode: 'EXPLORATION', gameTurn: 0, worldTimeSeconds: 0,
    location: { id: 'ford-camp', name: 'Ford Camp', nameLocale: 'en', nameFallback: false }, presentActors: [],
    resources: [{ key: 'vigor', minimum: 0, current: 8, maximum: 8 }], encounter: null, pendingDecision: 'STARTING_SITUATION',
    awaitingNarrationTotal: 0, awaitingNarration: [], dependencies: { status: 'AVAILABLE', mutationPaused: false, issues: [] },
    continuity: { checkpoint: 'VALID', publicSummary: 'NONE' } },
  panels: { scope: SCOPE, locale: 'en', contentAvailable: true, clock: { seconds: 0, day: 1, hour: 0, minute: 0, second: 0 },
    character: { actorId: ACTOR, name: 'Mara', role: null, location: { id: 'ford-camp', name: english('Ford Camp') },
      attributes: [{ key: 'awareness', name: english('Awareness'), base: 0, effective: 0 }, { key: 'might', name: english('Might'), base: 0, effective: 0 }],
      skills: [], derived: [] },
    resources: [{ key: 'vigor', name: english('Vigor'), minimum: 0, current: 8, maximum: 8 }], conditions: [], abilities: [],
    inventory: [], equipment: [],
    quests: [{ id: 'crossing', name: english('Cross the Ford'), summary: english('Reach the far bank of the river.'), status: 'available',
      updatedRevision: 0, objectives: [{ id: 'reach', description: english('Reach the far bank.'), optional: false, satisfied: false }], outcome: null }],
    knowledge: { discoveriesTotal: 0, discoveries: [], promisesTotal: 0, promises: [] }, journal: { entries: [], nextBefore: null } },
};

/**
 * Simulated local server; `lose` makes the next creation POST fail like a dropped connection. Reads of the created
 * playthrough answer only after it was created, so the live view cannot render a playthrough that does not exist yet.
 */
class FakeServer {
  readonly posts: Record<string, any>[] = [];
  /** Every requested URL, in order. */
  readonly calls: string[] = [];
  /** True once a creation POST was answered with 201. */
  created = false;
  lose = 0;

  /** Serve one request; every creation POST is recorded before its outcome is decided. */
  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    this.calls.push(url);
    if (url === '/api/v1/session') return Promise.resolve(json({ csrfToken: 'token' }));
    const live = /^\/api\/v1\/campaigns\/([0-9a-f-]{36})\/(state|resume|panels|stream)\?adventureId=([0-9a-f-]{36})(&|$)/.exec(url);
    if (live && live[1] === CAMPAIGN && live[3] === ADVENTURE && this.created) {
      // An empty held poll that ends: the stream has nothing yet for a new playthrough.
      if (live[2] === 'stream') {
        return Promise.resolve(new Response(new ReadableStream({ start: (controller) => controller.close() }),
          { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } }));
      }
      return Promise.resolve(json(LIVE[live[2] as 'state' | 'resume' | 'panels']));
    }
    if (url === '/api/v1/campaigns' && init?.method === 'POST') {
      this.posts.push(JSON.parse(String(init.body)));
      if (this.lose > 0) { this.lose--; return Promise.reject(new TypeError('Network connection lost')); }
      this.created = true;
      return Promise.resolve(json({ campaignId: CAMPAIGN, actorId: ACTOR, contentRevisionId: REVISION }, 201));
    }
    if (url === `/api/v1/adventures/${ADVENTURE}`) {
      return Promise.resolve(json({ adventureId: ADVENTURE, title: 'Lantern Ford', genre: 'fantasy', sourceLocale: 'en',
        metadataRevision: 0, createdAt: AT, updatedAt: AT }));
    }
    if (url.startsWith('/api/v1/packs?')) return Promise.resolve(json({ packs: [pack()], nextAfter: null }));
    if (url.startsWith('/api/v1/campaigns?')) {
      return Promise.resolve(json({ campaigns: !this.created ? [] : [{ campaignId: CAMPAIGN, adventureId: ADVENTURE,
        contentRevisionId: REVISION, title: 'First watch', mode: 'EXPLORATION', stateRevision: 0, gameTurn: 0, updatedAt: AT,
        pendingDecisionAvailable: true }], nextAfter: null }));
    }
    return Promise.resolve(json({ code: 'CAMPAIGN_SCOPE_UNAVAILABLE' }, 404));
  };
}

/** Wait until the routed screen satisfies a predicate; the bounded loop fails a stuck screen. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50 && !ready(); attempt++) {
    await new Promise(/** Let pending promises and change detection run. */ (accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

/** Type into one input the way the browser reports it. */
function type(root: HTMLElement, selector: string, value: string): void {
  const input = root.querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Submit the creation form explicitly, as the player's button press does. */
function submit(root: HTMLElement): void {
  root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}

describe('Playthrough creation', /** Register creation checks. */ () => {
  let server: FakeServer;

  beforeEach(/** Each test gets a fresh simulated server, router and device storage. */ () => {
    server = new FakeServer();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Destroying the fixture aborts the live view's long-poll follower. */ () => TestBed.resetTestingModule());

  it('accepts only public pack metadata of the requested adventure', /** Validation fails closed. */ () => {
    expect(parsePacks({ packs: [pack()], nextAfter: null }, ADVENTURE)).toHaveLength(1);
    expect(() => parsePacks({ packs: [{ ...pack(), contentHash: 'a'.repeat(64) }], nextAfter: null }, ADVENTURE)).toThrow();
    expect(() => parsePacks({ packs: [pack(OTHER)], nextAfter: null }, ADVENTURE)).toThrow();
    expect(() => parsePacks({ packs: [pack(), pack()], nextAfter: null }, ADVENTURE)).toThrow();
    expect(() => parsePacks({ packs: [{ ...pack(), authors: [] }], nextAfter: null }, ADVENTURE)).toThrow();
    expect(() => parsePacks({ packs: [{ ...pack(), contentRevisionId: REVISION.toUpperCase() }], nextAfter: null }, ADVENTURE)).toThrow();
    expect(() => parsePacks({ packs: [], nextAfter: null, owner: 'x' }, ADVENTURE)).toThrow();
    expect(() => parseCreated({ campaignId: CAMPAIGN, actorId: ACTOR, contentRevisionId: REVISION, seed: 'x' }, REVISION)).toThrow();
    expect(() => parseCreated({ campaignId: CAMPAIGN, actorId: ACTOR, contentRevisionId: OTHER }, REVISION)).toThrow();
    expect(parseCreated({ campaignId: CAMPAIGN, actorId: ACTOR, contentRevisionId: REVISION }, REVISION).campaignId).toBe(CAMPAIGN);
  });

  it('creates only on submit, retries a lost reply with the same identity and opens the playthrough', /** The creation journey. */ async () => {
    const harness = await RouterTestingHarness.create(`/adventures/${ADVENTURE}/create`);
    const root = harness.routeNativeElement!;
    await settle(harness, () => !!root.querySelector(`[data-revision-id="${REVISION}"]`));
    expect(root.textContent).toContain('Project contributors');
    expect(root.querySelector<HTMLInputElement>(`#revision-${REVISION}`)!.checked).toBe(true);
    type(root, '#playthrough-title', '  First watch ');
    type(root, '#character-name', 'Mara');
    await harness.fixture.whenStable();
    expect(server.posts).toHaveLength(0);

    type(root, '#character-name', '   ');
    submit(root);
    await harness.fixture.whenStable();
    expect(server.posts).toHaveLength(0);
    expect(root.textContent).toContain('Enter a name of 1–60 characters.');

    type(root, '#character-name', 'Mara');
    server.lose = 1;
    submit(root);
    await settle(harness, () => root.textContent!.includes('The reply was lost'));
    expect(server.posts).toHaveLength(1);
    const first = server.posts[0];
    expect(first).toEqual({ requestId: first['requestId'], adventureId: ADVENTURE, contentRevisionId: REVISION, title: 'First watch',
      narrativeLocale: 'en', choices: { name: 'Mara', attributes: {}, skills: {} } });
    expect(TestBed.inject(Router).url).toBe(`/adventures/${ADVENTURE}/create`);

    submit(root);
    await settle(harness, () => TestBed.inject(Router).url.startsWith('/saved/'));
    expect(server.posts).toHaveLength(2);
    expect(server.posts[1]).toEqual(first);
    expect(TestBed.inject(Router).url).toBe(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const opened = harness.routeNativeElement!;
    await settle(harness, () => !!opened.querySelector(`.selected-playthrough[data-campaign-id="${CAMPAIGN}"]`));

    // The live campaign view of the new playthrough renders its state, resume card and panels, not an error.
    await settle(harness, () => !!opened.querySelector(`.campaign-playthrough[data-campaign-id="${CAMPAIGN}"] [data-panels]`));
    const live = opened.querySelector<HTMLElement>(`.campaign-playthrough[data-campaign-id="${CAMPAIGN}"]`)!;
    expect(live.querySelector('#campaign-title')!.textContent).toBe('First watch');
    const passage = live.querySelector('.starting-situation .passage')!;
    expect(passage.textContent).toBe(SITUATION);
    expect(passage.getAttribute('lang')).toBe('en');
    expect(live.querySelector('[data-fallback]')).toBeNull();
    expect(live.querySelector('[data-location]')!.textContent).toContain('Ford Camp');
    expect(live.querySelector('[data-paused]')).toBeNull();
    expect(live.querySelector('[data-sheet] h4')!.textContent).toBe('Mara');
    expect(live.querySelector('[data-error]')).toBeNull();
    for (const read of ['state', 'resume', 'panels']) {
      expect(server.calls).toContain(`/api/v1/campaigns/${CAMPAIGN}/${read}?adventureId=${ADVENTURE}`);
    }
    // Opening the playthrough wrote nothing more: the two creation attempts are the only writes.
    expect(server.posts).toHaveLength(2);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
  });

  it('uses a new identity when the choices change after a lost reply', /** Identity follows the choices. */ async () => {
    const harness = await RouterTestingHarness.create(`/adventures/${ADVENTURE}/create`);
    const root = harness.routeNativeElement!;
    await settle(harness, () => !!root.querySelector('form'));
    type(root, '#playthrough-title', 'First watch');
    type(root, '#character-name', 'Mara');
    server.lose = 2;
    submit(root);
    await settle(harness, () => server.posts.length === 1 && root.textContent!.includes('The reply was lost'));
    type(root, '#character-name', 'Iris');
    submit(root);
    await settle(harness, () => server.posts.length === 2 && !root.querySelector('[role="status"]'));
    expect(server.posts[1]['choices']['name']).toBe('Iris');
    expect(server.posts[1]['requestId']).not.toBe(server.posts[0]['requestId']);
  });

  it('keeps the preview creation screen for preview identities', /** The matcher only takes UUIDs. */ async () => {
    const harness = await RouterTestingHarness.create('/adventures/mosslight/create');
    expect(harness.routeNativeElement!.textContent).toContain('This preview does not save it.');
    expect(server.posts).toHaveLength(0);
  });
});
