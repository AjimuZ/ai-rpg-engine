/**
 * Saved-library isolation checks: strict payload validation, session re-bootstrap, equal-title
 * separation, scoped playthrough selection and stale-reply rejection when switching adventures.
 * A simulated local server replaces `fetch`; failures identify the broken isolation property.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH, parseAdventure, parseAdventurePage, parsePlaythroughs, SavedLibraryClient } from './saved-library-client';

const FANTASY = '00000000-0000-4000-8000-00000000000a';
const CYBER = '00000000-0000-4000-8000-00000000000b';
const FANTASY_RUN = '00000000-0000-4000-8000-0000000000a1';
const CYBER_RUN = '00000000-0000-4000-8000-0000000000b1';
const REVISION = '00000000-0000-4000-8000-0000000000ff';
const AT = '2026-10-25T10:00:00Z';

/** Build an adventure summary; both test adventures deliberately share one title. */
function adventure(adventureId: string, genre: string) {
  return { adventureId, title: 'The Lantern', genre, sourceLocale: 'en', metadataRevision: 0, createdAt: AT, updatedAt: AT };
}

/** Build a playthrough row; equal titles across adventures prove identity-only selection. */
function playthrough(campaignId: string, adventureId: string) {
  return { campaignId, adventureId, contentRevisionId: REVISION, title: 'Mara', mode: 'EXPLORATION', stateRevision: 1,
    gameTurn: 3, worldTimeSeconds: 0, eventSequence: 2, updatedAt: AT, pendingDecisionAvailable: false };
}

/** Return a JSON response with the server's content type. */
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/**
 * Simulated local server. `hold` names an adventure whose reads stay pending until released,
 * which lets a test switch adventures while an older reply is still in flight.
 */
class FakeServer {
  readonly calls: string[] = [];
  readonly aborted: string[] = [];
  denyNext = 0;
  private readonly held = new Map<string, () => void>();
  hold = '';

  /** Serve one request; aborted pending requests reject like the browser does. */
  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    this.calls.push(url);
    if (url === '/api/v1/session') return Promise.resolve(json({ csrfToken: 'token' }));
    if (this.denyNext > 0) { this.denyNext--; return Promise.resolve(json({ code: 'LOCAL_ADMISSION_DENIED' }, 403)); }
    const reply = () => this.route(url);
    if (this.hold && url.includes(this.hold)) {
      return new Promise((accept, reject) => {
        init?.signal?.addEventListener('abort', /** Mirror browser abort semantics. */ () => { this.aborted.push(url); reject(new DOMException('Aborted', 'AbortError')); });
        this.held.set(url, /** Deliver the old reply late. */ () => accept(reply()));
      });
    }
    return Promise.resolve(reply());
  };

  /** Release every held reply, as a slow server finally answering. */
  release(): void {
    for (const deliver of this.held.values()) deliver();
    this.held.clear();
  }

  /** Map a path to the canned persisted state of the two same-title adventures. */
  private route(url: string): Response {
    if (url.startsWith('/api/v1/adventures?')) return json({ adventures: [adventure(FANTASY, 'Fantasy'), adventure(CYBER, 'Cyberpunk')], nextAfter: null });
    if (url === `/api/v1/adventures/${FANTASY}`) return json(adventure(FANTASY, 'Fantasy'));
    if (url === `/api/v1/adventures/${CYBER}`) return json(adventure(CYBER, 'Cyberpunk'));
    if (url.includes(`adventureId=${FANTASY}`)) return json({ campaigns: [playthrough(FANTASY_RUN, FANTASY)], nextAfter: null });
    if (url.includes(`adventureId=${CYBER}`)) return json({ campaigns: [playthrough(CYBER_RUN, CYBER)], nextAfter: null });
    return json({ code: 'ADVENTURE_SCOPE_UNAVAILABLE' }, 404);
  }
}

/** Wait until the routed screen satisfies a predicate; the bounded loop fails a stuck screen. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50 && !ready(); attempt++) {
    await new Promise(/** Let pending promises and change detection run. */ (accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

describe('Saved library', /** Register persisted-library isolation checks. */ () => {
  let server: FakeServer;

  beforeEach(/** Each test gets a fresh simulated server and router. */ () => {
    server = new FakeServer();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch }] });
  });

  it('rejects malformed, foreign and duplicate payloads instead of rendering them', /** Validation fails closed. */ () => {
    expect(() => parseAdventure({ ...adventure(FANTASY, 'Fantasy'), adventureId: FANTASY.toUpperCase() })).toThrow();
    expect(() => parseAdventure({ ...adventure(FANTASY, 'x'.repeat(81)) })).toThrow();
    expect(() => parseAdventure({ ...adventure(FANTASY, 'Fantasy'), metadataRevision: 2 ** 53 })).toThrow();
    expect(() => parseAdventure({ ...adventure(FANTASY, 'Fantasy'), sourceLocale: 'fr' })).toThrow();
    expect(() => parseAdventurePage({ adventures: [adventure(FANTASY, 'A'), adventure(FANTASY, 'B')], nextAfter: null })).toThrow();
    expect(() => parsePlaythroughs({ campaigns: [playthrough(CYBER_RUN, CYBER)], nextAfter: null }, FANTASY)).toThrow();
    expect(parsePlaythroughs({ campaigns: [playthrough(FANTASY_RUN, FANTASY)], nextAfter: null }, FANTASY)).toHaveLength(1);
  });

  it('bootstraps the session again exactly once after a denial', /** Expired cookies recover without loops. */ async () => {
    const client = TestBed.inject(SavedLibraryClient);
    server.denyNext = 1;
    expect((await client.adventure(FANTASY, new AbortController().signal)).genre).toBe('Fantasy');
    expect(server.calls.filter(/** Count bootstraps. */ (url) => url === '/api/v1/session')).toHaveLength(2);
    server.denyNext = 2;
    await expect(client.adventure(FANTASY, new AbortController().signal)).rejects.toMatchObject({ kind: 'denied' });
  });

  it('keeps equal titles as separate identities on the library page', /** Cards are keyed by UUID. */ async () => {
    const harness = await RouterTestingHarness.create('/library');
    const root = harness.routeNativeElement!;
    await settle(harness, () => root.querySelectorAll('.saved-card').length === 2);
    const cards = [...root.querySelectorAll<HTMLElement>('.saved-card')];
    expect(cards.map(/** Same visible title. */ (card) => card.querySelector('h3')!.textContent)).toEqual(['The Lantern', 'The Lantern']);
    expect(cards.map(/** Distinct identities and links. */ (card) => card.querySelector('a')!.getAttribute('href'))).toEqual([`/saved/${FANTASY}`, `/saved/${CYBER}`]);
    expect(cards[0].textContent).toContain('Fantasy');
    expect(cards[1].textContent).toContain('Cyberpunk');
  });

  it('shows an explained outage when the library cannot be read', /** No silent empty list. */ async () => {
    TestBed.overrideProvider(LIBRARY_FETCH, { useValue: () => Promise.resolve(json({ code: 'LOCAL_OWNER_UNCONFIGURED' }, 503)) });
    const harness = await RouterTestingHarness.create('/library');
    const root = harness.routeNativeElement!;
    await settle(harness, () => !!root.querySelector('.saved-library [role=alert]'));
    expect(root.querySelector('.saved-library')!.textContent).toContain('not configured');
    expect(root.querySelector('.saved-card')).toBeNull();
  });

  it('selects playthroughs only inside the routed adventure', /** Foreign playthrough IDs are refused. */ async () => {
    const harness = await RouterTestingHarness.create(`/saved/${FANTASY}?playthrough=${FANTASY_RUN}`);
    const root = harness.routeNativeElement!;
    await settle(harness, () => !!root.querySelector('.saved-heading'));
    expect(root.querySelector('.saved-heading')!.getAttribute('data-adventure-id')).toBe(FANTASY);
    expect(root.querySelector('.selected-playthrough')!.getAttribute('data-campaign-id')).toBe(FANTASY_RUN);
    // Chrome showed the title and turn run together: the compiler drops line-break whitespace between elements.
    expect(root.querySelector(`.saved-list [data-campaign-id="${FANTASY_RUN}"]`)!.textContent).toMatch(/\S Turn 3/);
    await harness.navigateByUrl(`/saved/${FANTASY}?playthrough=${CYBER_RUN}`);
    await settle(harness, () => !!root.querySelector('[role=alert]'));
    expect(root.querySelector('.selected-playthrough')!.getAttribute('data-campaign-id')).toBeNull();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('does not belong');
  });

  it('cancels and ignores a late reply after switching adventures', /** Stale callbacks cannot mix scopes. */ async () => {
    const harness = await RouterTestingHarness.create('/library');
    server.hold = FANTASY;
    await harness.navigateByUrl(`/saved/${FANTASY}`);
    const root = harness.routeNativeElement!;
    await harness.navigateByUrl(`/saved/${CYBER}?playthrough=${CYBER_RUN}`);
    await settle(harness, () => root.querySelector('.saved-heading')?.getAttribute('data-adventure-id') === CYBER);
    server.release();
    await harness.fixture.whenStable();
    await new Promise(/** Give any stale reply a chance to land. */ (accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
    expect(server.aborted.length).toBeGreaterThan(0);
    expect(root.querySelector('.saved-heading')!.getAttribute('data-adventure-id')).toBe(CYBER);
    expect(root.querySelector('.saved-heading')!.textContent).toContain('Cyberpunk');
    expect(root.querySelector('.saved-heading')!.textContent).not.toContain('Fantasy');
    expect([...root.querySelectorAll('li[data-campaign-id]')].map(/** Only this adventure's rows. */ (row) => row.getAttribute('data-campaign-id'))).toEqual([CYBER_RUN]);
  });

  it('refuses a non-canonical adventure identity without contacting the server', /** Route IDs are UUIDs. */ async () => {
    const harness = await RouterTestingHarness.create('/saved/The%20Lantern');
    const root = harness.routeNativeElement!;
    await settle(harness, () => !!root.querySelector('[role=alert]'));
    expect(root.textContent).toContain('unavailable');
    expect(server.calls.some(/** No adventure read for a title. */ (url) => url.includes('Lantern'))).toBe(false);
  });
});
