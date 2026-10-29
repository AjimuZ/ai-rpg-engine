/** Panels and journal (P10.5): strict parsing, localized rendering, hidden-name fallback and older-event paging. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { CampaignProtocolError, parsePlayPanels } from './campaign-protocol';
import { clockMessage, conditionMessage, promiseDueMessage, promiseMessage, stanceMessage } from './campaign-messages';
import { ADVENTURE, CAMPAIGN, CampaignServer, MemoryStore, panelsBody, syncProvider } from './fixtures';

/** Wait until the routed screen satisfies a predicate. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

/** Clone a body and change it, so a spec can break exactly one field. */
function mutate(change: (body: Record<string, any>) => void): unknown {
  const body = JSON.parse(JSON.stringify(panelsBody())) as Record<string, any>;
  change(body);
  return body;
}

describe('Panels parser', /** Every shape the server sends is checked; nothing partial reaches a screen. */ () => {
  it('reads a complete body', /** Values are carried exactly as sent. */ () => {
    const panels = parsePlayPanels(panelsBody(), ADVENTURE, CAMPAIGN);
    expect(panels.stateRevision).toBe(2);
    expect(panels.clock).toEqual({ seconds: 93_660, day: 2, hour: 2, minute: 1, second: 0 });
    expect(panels.character?.attributes[0]).toEqual({ key: 'awareness', name: { text: 'Awareness', locale: 'en', fallback: false }, base: 3, effective: 1 });
    expect(panels.quests.map((quest) => quest.status)).toEqual(['active', 'completed']);
    expect(panels.quests[1].outcome?.rewards[0].quantity).toBe(5);
    expect(panels.journal.map((entry) => entry.sequence)).toEqual([4, 3]);
    expect(panels.nextBefore).toBeNull();
  });

  it('accepts a body without a character or any pack name', /** A legacy campaign and unavailable content stay readable. */ () => {
    const panels = parsePlayPanels(mutate((body) => { body['character'] = null; body['contentAvailable'] = false; body['resources'][0]['name'] = null; }), ADVENTURE, CAMPAIGN);
    expect(panels.character).toBeNull();
    expect(panels.resources[0].name).toBeNull();
  });

  it.each<[string, (body: Record<string, any>) => void]>([
    ['a foreign campaign', (body) => { body['scope']['campaignId'] = '00000000-0000-4000-8000-0000000000ff'; }],
    ['an unknown quest status', (body) => { body['quests'][0]['status'] = 'undiscovered'; }],
    ['an unknown stance', (body) => { body['knowledge']['discoveries'][0]['stance'] = 'CERTAIN'; }],
    ['an impossible hour', (body) => { body['clock']['hour'] = 24; }],
    ['a negative quantity', (body) => { body['inventory'][0]['quantity'] = -1; }],
    ['a non-integer value', (body) => { body['resources'][0]['current'] = 1.5; }],
    ['a zero journal cursor', (body) => { body['journal']['nextBefore'] = 0; }],
    ['an oversized journal page', (body) => { body['journal']['entries'] = Array.from({ length: 101 }, () => body['journal']['entries'][0]); }],
    ['a blank statement', (body) => { body['journal']['entries'][0]['statement'] = ' '; }],
    ['more listed discoveries than the total', (body) => { body['knowledge']['discoveriesTotal'] = 0; }],
    ['an unsupported text language', (body) => { body['quests'][0]['name']['locale'] = 'fr'; }],
    ['a non-object body', (body) => { for (const key of Object.keys(body)) delete body[key]; }],
  ])('refuses %s', /** One broken field rejects the whole body. */ (_name, change) => {
    expect(() => parsePlayPanels(mutate(change), ADVENTURE, CAMPAIGN)).toThrow(CampaignProtocolError);
  });
});

describe('Panels wording', /** Pure formatting of the display lines. */ () => {
  it('numbers days from one and pads the time', /** Matches the server's calendar reading. */ () => {
    expect(clockMessage({ seconds: 0, day: 1, hour: 0, minute: 0, second: 0 }, 'en')).toBe('Day 1, 00:00');
    expect(clockMessage({ seconds: 93_660, day: 2, hour: 2, minute: 1, second: 0 }, 'en')).toBe('Day 2, 02:01');
  });

  it('describes conditions, stances and promises without claiming truth', /** Stances name how the player holds a statement. */ () => {
    const panels = parsePlayPanels(panelsBody(), ADVENTURE, CAMPAIGN);
    expect(conditionMessage(panels.conditions[0], 'en')).toBe('Dazed ×1, 3 rounds left');
    expect(stanceMessage('RUMORED')).toBe('A rumor you heard');
    expect(promiseMessage(panels.promises[0])).toBe('Promise · you owe Warden');
    expect(promiseDueMessage(panels.promises[0], 'en')).toBe('Due Day 2, 03:46');
    expect(promiseDueMessage({ ...panels.promises[0], overdue: true }, 'en')).toBe('Overdue since Day 2, 03:46');
    expect(promiseDueMessage({ ...panels.promises[0], dueWorldTime: null }, 'en')).toBeNull();
  });
});

describe('Panels in the playthrough view', /** The routed view shows the server's panels and never submits anything. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server and router per test. */ () => {
    server = new CampaignServer();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  /** Open the playthrough and wait for the panels. */
  async function open(): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-panels]') !== null);
    current = harness;
    return root;
  }
  let current: RouterTestingHarness;

  it('shows the clock, sheet, conditions, equipment, quests, knowledge and events', /** Everything comes from one revision. */ async () => {
    const root = await open();
    expect(root.querySelector('[data-clock]')!.textContent).toContain('Day 2, 02:01');
    expect(root.querySelector('[data-place]')!.textContent).toContain('Lantern Shelter');
    // An attribute changed by a condition shows the effective value and the base.
    expect(root.querySelector('[data-attribute="awareness"] dd')!.textContent).toBe('1 (base 3)');
    expect(root.querySelector('[data-attribute="might"] dd')!.textContent).toBe('2');
    expect(root.querySelector('[data-resource="vigor"]')!.textContent).toContain('6 / 8');
    expect(root.querySelector('[data-resource="vigor"] meter')!.getAttribute('aria-label')).toBe('Vigor');
    expect(root.querySelector('[data-condition="dazed"]')!.textContent).toContain('3 rounds left');
    expect(root.querySelector('[data-item="sword"]')!.textContent).toContain('Worn (main-hand)');
    expect(root.querySelector('[data-item="coin"]')!.textContent).toContain('× 10');
    expect(root.querySelector('[data-slot="main-hand"]')!.textContent).toContain('Sword');
    const survey = root.querySelector('[data-quest="survey"]')!;
    expect(survey.textContent).toContain('In progress');
    expect(survey.querySelector('[data-objective="reach"]')!.textContent).toContain('Done');
    expect(survey.querySelector('[data-objective="provisions"]')!.textContent).toContain('Optional');
    const gate = root.querySelector('[data-quest="gate"]')!;
    expect(gate.querySelector('[data-outcome]')!.textContent).toContain('Success');
    expect(gate.querySelector('.rewards')!.textContent).toContain('Coin × 5');
    expect(root.querySelector('[data-stance="RUMORED"]')!.textContent).toContain('A rumor you heard');
    expect(root.querySelector('[data-direction="YOU_OWE"]')!.textContent).toContain('you owe Warden');
    expect([...root.querySelectorAll('[data-event]')].map((node) => node.getAttribute('data-event'))).toEqual(['4', '3']);
    expect(root.querySelector('[data-older]')).toBeNull();
    // Reading the panels only issued GETs.
    expect(server.calls.some((url) => url.includes('/panels?'))).toBe(true);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
  });

  it('falls back to identifiers and says so when pack names are unavailable', /** Hidden names are never invented. */ async () => {
    server.panelsBody = panelsBody({ contentAvailable: false });
    const root = await open();
    expect(root.querySelector('[data-content-missing]')!.textContent).toContain('cannot be shown');
    expect(root.querySelector('[data-item="sword"]')!.textContent).toContain('sword');
    expect(root.querySelector('[data-resource="vigor"]')!.textContent).toContain('vigor');
    expect(root.querySelector('[data-attribute="awareness"] dt')!.textContent).toBe('awareness');
  });

  it('discloses source-language text and tags its language', /** A fallback quest text shows the shared notice. */ async () => {
    server.panelsBody = mutate((body) => { body['quests'][0]['name'] = { text: 'Trova la pietra', locale: 'it', fallback: true }; });
    const root = await open();
    const survey = root.querySelector('[data-quest="survey"]')!;
    expect(survey.querySelector('strong')!.getAttribute('lang')).toBe('it');
    expect(survey.querySelector('[data-fallback]')!.textContent).toContain('original Italian');
  });

  it('loads older journal events on request and appends them after the newest', /** The cursor comes from the server. */ async () => {
    server.panelsBody = panelsBody({ nextBefore: 3 });
    const older = JSON.parse(JSON.stringify(panelsBody()));
    older.journal = { entries: [{ sequence: 2, type: 'QUEST_ACCEPTED', stateRevision: 1, clock: { seconds: 0, day: 1, hour: 0, minute: 0, second: 0 },
      statement: 'Mara accepts the quest survey.' }], nextBefore: null };
    server.olderPages.set(3, older);
    const root = await open();
    const button = root.querySelector('[data-older]') as HTMLButtonElement;
    expect(button).not.toBeNull();
    button.click();
    await settle(current, () => root.querySelectorAll('[data-event]').length === 3);
    expect([...root.querySelectorAll('[data-event]')].map((node) => node.getAttribute('data-event'))).toEqual(['4', '3', '2']);
    expect(root.querySelector('[data-older]')).toBeNull();
    expect(server.calls.filter((url) => url.includes('before=3')).length).toBe(1);
  });
});
