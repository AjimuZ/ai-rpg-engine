/**
 * Browser isolation of two saved adventures that share every display name (QA-33, MVP scope, browser part):
 * drafts and stream cursors are keyed by adventure identity, switching aborts pending reads, and a reply
 * that arrives after the switch never reaches the other adventure's view. A simulated local server serves
 * both adventures; it also records any request that pairs one adventure's playthrough with the other's ID.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { ADVENTURE, CAMPAIGN, json, MemoryStore, OP1, operation, panelsBody, resumeCard, state, syncProvider } from './fixtures';

/** One saved genre: identities differ, while the adventure title, playthrough title, NPC and items are equal. */
interface Genre { readonly adventureId: string; readonly campaignId: string; readonly genre: string; readonly situation: string; readonly location: string }

const FANTASY: Genre = { adventureId: ADVENTURE, campaignId: CAMPAIGN, genre: 'Fantasy',
  situation: 'The lantern burns on the shelter table.', location: 'Lantern Shelter' };
const CYBERPUNK: Genre = { adventureId: '00000000-0000-4000-8000-00000000000b', campaignId: '00000000-0000-4000-8000-0000000000b1',
  genre: 'Cyberpunk', situation: 'The lantern sign buzzes over the wet alley.', location: 'Lantern Alley' };
const GENRES = [FANTASY, CYBERPUNK];

/** Storage keys written by the browser for one scope; both embed the adventure ID before the playthrough ID. */
const draftKey = (genre: Genre) => `engine.story-draft.v1:["${genre.adventureId}","${genre.campaignId}"]`;
const syncKey = (genre: Genre) => `engine.campaign-sync.v1:["${genre.adventureId}","${genre.campaignId}"]`;

/** Re-scope a fixture body written for the fantasy adventure to another genre's identities and place name. */
function scoped(body: unknown, genre: Genre): unknown {
  return JSON.parse(JSON.stringify(body).replaceAll(ADVENTURE, genre.adventureId).replaceAll(CAMPAIGN, genre.campaignId)
    .replaceAll(FANTASY.location, genre.location));
}

/**
 * Simulated local server for both genres. `hold` names a URL fragment whose requests stay pending until
 * {@link release}; with `ignoreAbort` the held reply is still delivered after the caller aborted, like a
 * transport that cannot cancel, so the screen's own scoping is what must reject it.
 */
class TwoGenreServer {
  readonly calls: string[] = [];
  readonly aborted: string[] = [];
  /** Requests whose adventure query did not match the playthrough's real adventure. */
  readonly mismatched: string[] = [];
  hold = '';
  ignoreAbort = false;
  private readonly held: (() => void)[] = [];

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    this.calls.push(url);
    if (url === '/api/v1/session') return Promise.resolve(json({ csrfToken: 'token' }));
    if (this.hold && url.includes(this.hold)) {
      return new Promise((accept, reject) => {
        init?.signal?.addEventListener('abort', () => {
          this.aborted.push(url);
          if (!this.ignoreAbort) reject(new DOMException('Aborted', 'AbortError'));
        });
        this.held.push(() => accept(this.route(url)));
      });
    }
    return Promise.resolve(this.route(url));
  };

  /** Deliver every held reply, as a slow server finally answering. */
  release(): void {
    for (const deliver of this.held.splice(0)) deliver();
  }

  private route(url: string): Response {
    const detail = /^\/api\/v1\/adventures\/([0-9a-f-]{36})$/.exec(url);
    if (detail) {
      const genre = GENRES.find((item) => item.adventureId === detail[1]);
      return genre ? json({ adventureId: genre.adventureId, title: 'The Lantern', genre: genre.genre, sourceLocale: 'en', metadataRevision: 0,
        createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z' }) : json({ code: 'ADVENTURE_SCOPE_UNAVAILABLE' }, 404);
    }
    const list = /^\/api\/v1\/campaigns\?adventureId=([0-9a-f-]{36})/.exec(url);
    if (list) {
      const genre = GENRES.find((item) => item.adventureId === list[1]);
      return genre ? json({ campaigns: [(scoped(state(), genre) as { campaign: unknown }).campaign], nextAfter: null })
        : json({ code: 'ADVENTURE_SCOPE_UNAVAILABLE' }, 404);
    }
    const read = /^\/api\/v1\/campaigns\/([0-9a-f-]{36})\/(state|resume|panels|stream|operations\/[0-9a-f-]{36})\?adventureId=([0-9a-f-]{36})/.exec(url);
    const genre = read && GENRES.find((item) => item.campaignId === read[1]);
    if (!read || !genre) return json({ code: 'CAMPAIGN_SCOPE_UNAVAILABLE' }, 404);
    if (genre.adventureId !== read[3]) { this.mismatched.push(url); return json({ code: 'CAMPAIGN_SCOPE_UNAVAILABLE' }, 404); }
    switch (read[2]) {
      case 'state': return json({ ...(scoped(state({ fallback: false, locale: 'en' }), genre) as object),
        startingSituation: { text: genre.situation, locale: 'en', fallback: false } });
      case 'resume': return json({ ...(scoped(resumeCard(), genre) as object), location: { id: 'trailhead', name: genre.location, nameLocale: 'en', nameFallback: false } });
      case 'panels': return json(scoped(panelsBody(), genre));
      case 'stream': {
        // A closed empty response behaves like a held long poll that ended without messages.
        const body = new ReadableStream<Uint8Array>({ start(controller) { controller.close(); } });
        return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } });
      }
      default: return genre === FANTASY && read[2] === `operations/${OP1}`
        ? json(scoped(operation(OP1, 'DELIVERED', 5, true), genre)) : json({ code: 'COMMAND_SCOPE_UNAVAILABLE' }, 404);
    }
  }
}

/** Wait until the routed screen satisfies a predicate; the bounded loop fails a stuck screen. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

/** Let any reply that is still in flight land, then settle change detection. */
async function flush(harness: RouterTestingHarness): Promise<void> {
  for (let round = 0; round < 3; round++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
}

describe('Saved campaigns of two same-name adventures', /** Register browser isolation checks against a two-genre server. */ () => {
  let server: TwoGenreServer;
  let drafts: MemoryStore;
  let sync: MemoryStore;
  let harness: RouterTestingHarness;
  let root: HTMLElement;

  beforeEach(/** Fresh server, router and storages per test. */ async () => {
    server = new TwoGenreServer();
    drafts = new MemoryStore();
    sync = new MemoryStore();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: drafts }, syncProvider(sync)] });
    harness = await RouterTestingHarness.create();
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  /** Navigate to a genre's saved playthrough and wait for its own starting situation to render. */
  async function open(genre: Genre): Promise<void> {
    await harness.navigateByUrl(`/saved/${genre.adventureId}?playthrough=${genre.campaignId}`);
    root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('.saved-heading')?.getAttribute('data-adventure-id') === genre.adventureId &&
      root.querySelector('.starting-situation .passage')?.textContent === genre.situation);
  }

  const field = () => root.querySelector('#campaign-input') as HTMLTextAreaElement;

  /** Type like a player: set the value and selection, then fire the native input event. */
  function type(text: string): void {
    field().value = text;
    field().setSelectionRange(text.length, text.length);
    field().dispatchEvent(new Event('input'));
  }

  it('keeps a draft typed in one adventure out of the other adventure with the same names', /** Draft keys embed the adventure ID. */ async () => {
    await open(FANTASY);
    expect(root.querySelector('.saved-heading strong')!.textContent).toBe('The Lantern');
    expect(field().value).toBe('');
    type('Raise the lantern');

    await open(CYBERPUNK);
    expect(root.querySelector('.saved-heading strong')!.textContent).toBe('The Lantern');
    expect(root.querySelector('.campaign-playthrough')!.getAttribute('data-campaign-id')).toBe(CYBERPUNK.campaignId);
    expect(field().value).toBe('');
    expect(drafts.values.has(draftKey(CYBERPUNK))).toBe(false);
    type('Jack into the grid');

    await open(FANTASY);
    expect(field().value).toBe('Raise the lantern');
    await open(CYBERPUNK);
    expect(field().value).toBe('Jack into the grid');

    expect([...drafts.values.keys()].sort()).toEqual([draftKey(FANTASY), draftKey(CYBERPUNK)].sort());
    expect(drafts.values.get(draftKey(FANTASY))).toContain('Raise the lantern');
    expect(drafts.values.get(draftKey(FANTASY))).not.toContain('Jack into the grid');
    expect(drafts.values.get(draftKey(CYBERPUNK))).toContain('Jack into the grid');
    expect(drafts.values.get(draftKey(CYBERPUNK))).not.toContain('Raise the lantern');
    // Reading and drafting never submit: Send stays disabled and nothing was posted.
    expect((root.querySelector('[data-send]') as HTMLButtonElement).disabled).toBe(true);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
    expect(server.mismatched).toEqual([]);
  });

  it('keys the stream cursor and saved operations by adventure so neither leaks into the other', /** Sync keys embed the adventure ID. */ async () => {
    sync.setItem(syncKey(FANTASY), JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP1, commandType: 'USE_ABILITY', status: 'DELIVERED', operationVersion: 5 }] }));
    sync.setItem(syncKey(CYBERPUNK), JSON.stringify({ version: 1, cursor: '0:5', operations: [] }));

    await open(FANTASY);
    await settle(harness, () => root.querySelector('[data-operation-id]') !== null);
    await open(CYBERPUNK);
    await settle(harness, () => root.textContent!.includes('No actions have been recorded'));
    expect(root.querySelector('[data-operation-id]')).toBeNull();

    const streamAfter = (genre: Genre) => server.calls.map(decodeURIComponent).filter((url) =>
      url.includes(`/campaigns/${genre.campaignId}/stream?`) && url.includes(`adventureId=${genre.adventureId}`));
    expect(streamAfter(FANTASY).length).toBeGreaterThan(0);
    expect(streamAfter(CYBERPUNK).length).toBeGreaterThan(0);
    expect(streamAfter(FANTASY).every((url) => url.includes('after=0:1'))).toBe(true);
    expect(streamAfter(CYBERPUNK).every((url) => url.includes('after=0:5'))).toBe(true);
    // Only the fantasy scope ever asked about its operation, and always under its own adventure.
    const asked = server.calls.filter((url) => url.includes(`/operations/${OP1}?`));
    expect(asked.length).toBeGreaterThan(0);
    expect(asked.every((url) => url.includes(`/campaigns/${FANTASY.campaignId}/`) && url.includes(`adventureId=${FANTASY.adventureId}`))).toBe(true);

    expect([...sync.values.keys()].sort()).toEqual([syncKey(FANTASY), syncKey(CYBERPUNK)].sort());
    expect(sync.values.get(syncKey(FANTASY))).toContain('"cursor":"0:1"');
    expect(sync.values.get(syncKey(FANTASY))).toContain(OP1);
    expect(sync.values.get(syncKey(CYBERPUNK))).toContain('"cursor":"0:5"');
    expect(sync.values.get(syncKey(CYBERPUNK))).not.toContain(OP1);
    expect(server.mismatched).toEqual([]);
  });

  it('aborts the pending reads of the previous adventure when switching', /** Cancellation, not just rendering, is scoped. */ async () => {
    server.hold = `/campaigns/${FANTASY.campaignId}/state`;
    await harness.navigateByUrl(`/saved/${FANTASY.adventureId}?playthrough=${FANTASY.campaignId}`);
    await settle(harness, () => server.calls.some((url) => url.includes(server.hold)));
    expect(server.aborted).toEqual([]);
    expect(harness.routeNativeElement!.querySelector('.saved-heading')!.getAttribute('data-adventure-id')).toBe(FANTASY.adventureId);

    await open(CYBERPUNK);
    expect(server.aborted.length).toBe(1);
    expect(server.aborted[0]).toContain(`/campaigns/${FANTASY.campaignId}/state?adventureId=${FANTASY.adventureId}`);
    server.release();
    await flush(harness);

    expect(root.textContent).not.toContain(FANTASY.situation);
    expect(root.textContent).not.toContain(FANTASY.location);
    expect(root.querySelector('.campaign-playthrough')!.getAttribute('data-campaign-id')).toBe(CYBERPUNK.campaignId);
    // The aborted follower neither retried nor moved on to the later reads of the fantasy scope.
    expect(server.calls.filter((url) => url.includes(`/campaigns/${FANTASY.campaignId}/`))).toHaveLength(1);
    expect(drafts.values.size).toBe(0);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
    expect(server.mismatched).toEqual([]);
  });

  it('never shows a late reply from the previous adventure, even when its transport ignores the abort', /** The screen scopes replies itself. */ async () => {
    server.ignoreAbort = true;
    server.hold = `/campaigns/${FANTASY.campaignId}/state`;
    await harness.navigateByUrl(`/saved/${FANTASY.adventureId}?playthrough=${FANTASY.campaignId}`);
    await settle(harness, () => server.calls.some((url) => url.includes(server.hold)));

    await open(CYBERPUNK);
    type('Jack into the grid');
    server.release();
    await flush(harness);

    expect(server.aborted.length).toBe(1);
    expect(root.querySelector('.saved-heading')!.getAttribute('data-adventure-id')).toBe(CYBERPUNK.adventureId);
    expect(root.querySelector('.campaign-playthrough')!.getAttribute('data-campaign-id')).toBe(CYBERPUNK.campaignId);
    expect(root.querySelector('.starting-situation .passage')!.textContent).toBe(CYBERPUNK.situation);
    await settle(harness, () => root.querySelector('[data-location]')?.textContent?.includes(CYBERPUNK.location) === true);
    expect(root.textContent).not.toContain(FANTASY.situation);
    expect(root.textContent).not.toContain(FANTASY.location);
    // The late reply neither replaced the typed draft nor wrote under the fantasy key.
    expect(field().value).toBe('Jack into the grid');
    expect([...drafts.values.keys()]).toEqual([draftKey(CYBERPUNK)]);
    expect(server.mismatched).toEqual([]);
  });
});
