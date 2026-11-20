/**
 * Starting an offered fight (P13.2-B): strict parsing of the offers, `ENGAGE` built only for an offered template and a listed
 * opening target, never during a fight, never without the player's own read and confirmation, and a lost reply retried with the
 * same request identity.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { CampaignProtocolError, CombatView, parseCombatView } from './campaign-protocol';
import { entryEntries, resolveEntry } from './combat-entry';
import { refusalMessage } from './combat-messages';
import { ADVENTURE, CAMPAIGN, CampaignServer, encounterBody, MemoryStore, resumeCard, syncProvider } from './fixtures';

/** The fixture body outside a fight with its two offers, optionally changed first, parsed as the page parses it. */
function offered(change: (body: Record<string, any>) => void = () => undefined): CombatView {
  const body = JSON.parse(JSON.stringify(encounterBody({ none: true, offers: true }))) as Record<string, any>;
  change(body);
  return parseCombatView(body, ADVENTURE, CAMPAIGN);
}

describe('Offered encounters', /** Only what the projection offers can become an ENGAGE. */ () => {
  it('reads the offers exactly as sent and lists the plain start before each opening attack', /** Offer order is kept. */ () => {
    const view = offered();
    expect(view.encounter).toBeNull();
    expect(view.offers.map((offer) => offer.templateId)).toEqual(['trail-ambush', 'toll-parley']);
    expect(view.offers[0].participants).toEqual([{ participantId: 'raider', side: 'hostile', zone: 'yard' }, { participantId: 'keeper', side: 'ally', zone: 'entry' }]);
    expect(view.offers[0].objectives[1]).toEqual({ objectiveId: 'hold', kind: 'survive', zone: null, participantId: null, rounds: 3 });
    expect(entryEntries(view).map((entry) => entry.key)).toEqual(['engage:trail-ambush:', 'engage:trail-ambush:raider', 'engage:toll-parley:']);
    // A body without the member, as an older server would send, offers nothing.
    expect(offered((body) => { delete body['offers']; }).offers).toEqual([]);
  });

  it.each<[string, (body: Record<string, any>) => void]>([
    ['an offer during a fight', (body) => { Object.assign(body, encounterBody()); body['offers'] = encounterBody({ none: true, offers: true })['offers']; }],
    ['an ally as opening target', (body) => { body['offers'][0]['openingTargets'] = ['keeper']; }],
    ['an opening target under the forbidden policy', (body) => { body['offers'][1]['openingTargets'] = ['smuggler']; }],
    ['an unknown opening policy', (body) => { body['offers'][0]['openingPolicy'] = 'ambush'; }],
    ['the player as an offered participant', (body) => { body['offers'][0]['participants'][0]['side'] = 'player'; }],
    ['a repeated template', (body) => { body['offers'][1] = body['offers'][0]; }],
    ['an unknown stake', (body) => { body['offers'][0]['objectives'][0]['kind'] = 'conquer'; }],
  ])('refuses %s', /** One broken field rejects the whole body. */ (_name, change) => {
    expect(() => offered(change)).toThrow(CampaignProtocolError);
  });

  it('builds the closed ENGAGE payload only for an offered template and a listed opening target', /** Nothing else is sent. */ () => {
    const view = offered();
    const built = (templateId: string, openingStrike: string | null) => {
      const resolved = resolveEntry(view, { templateId, openingStrike });
      return resolved.ok ? resolved.command : resolved.refusal;
    };
    expect(built('trail-ambush', null)).toEqual({ commandType: 'ENGAGE', payload: { encounterId: 'trail-ambush' } });
    expect(built('trail-ambush', 'raider')).toEqual({ commandType: 'ENGAGE', payload: { encounterId: 'trail-ambush', openingStrike: 'raider' } });
    expect(built('toll-parley', null)).toEqual({ commandType: 'ENGAGE', payload: { encounterId: 'toll-parley' } });
    expect(built('toll-parley', 'smuggler')).toBe('NOT_LISTED');
    expect(built('trail-ambush', 'keeper')).toBe('NOT_LISTED');
    expect(built('grove-watch', null)).toBe('NOT_LISTED');
    // During a fight, and without a projection, nothing can be started.
    const fighting = parseCombatView(encounterBody(), ADVENTURE, CAMPAIGN);
    expect(entryEntries(fighting)).toEqual([]);
    expect(resolveEntry(fighting, { templateId: 'trail-ambush', openingStrike: null })).toEqual({ ok: false, refusal: 'NOT_YOUR_TURN' });
    expect(resolveEntry(null, { templateId: 'trail-ambush', openingStrike: null })).toEqual({ ok: false, refusal: 'NOT_YOUR_TURN' });
    expect(refusalMessage('COMMAND_ENCOUNTER_UNAVAILABLE')).toBe('That fight cannot be started now. Nothing changed.');
  });
});

describe('Starting a fight in the playthrough view', /** Read on request, review first, send only on confirmation. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server and router per test, outside a fight. */ () => {
    server = new CampaignServer();
    server.encounterBody = encounterBody({ none: true, offers: true });
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    expect(ready()).toBe(true);
  }
  async function open(): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-look-for-fights]') !== null && root.querySelector('[data-panels]') !== null);
    return { harness, root };
  }
  const text = (root: HTMLElement, selector: string) => (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  async function press(harness: RouterTestingHarness, root: HTMLElement, selector: string): Promise<void> {
    (root.querySelector(selector) as HTMLButtonElement).click();
    await new Promise((accept) => setTimeout(accept, 0));
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  }
  const reads = () => server.calls.filter((url) => url.includes('/encounter?')).length;

  it('reads the offers only when asked and shows only what entry reveals, sending nothing', /** Reading is not starting. */ async () => {
    const { harness, root } = await open();
    expect(reads()).toBe(0);
    expect(root.querySelector('[data-offers]')).toBeNull();
    await press(harness, root, '[data-look-for-fights]');
    await settle(harness, () => root.querySelector('[data-offers]') !== null);
    expect(reads()).toBe(1);
    expect(text(root, '[data-offer="trail-ambush"] [data-offer-start]')).toBe('You start in entry');
    expect(text(root, '[data-offer="trail-ambush"] [data-participant="raider"]')).toBe('raider · Hostile · yard');
    expect(text(root, '[data-offer="trail-ambush"] [data-participant="keeper"]')).toBe('keeper · Ally · entry');
    expect(text(root, '[data-offer="trail-ambush"] [data-offer-escape]')).toBe('escape-route');
    expect([...root.querySelectorAll('[data-offer="trail-ambush"] [data-stake]')].map((stake) => stake.textContent!.trim()))
      .toEqual(['Defeat every hostile participant', 'Survive 3 rounds']);
    expect(text(root, '[data-offer="toll-parley"] [data-offer-opening]')).toBe('This fight cannot be started with an attack.');
    expect([...root.querySelectorAll('[data-entry]')].map((button) => button.textContent!.trim())).toEqual([
      'Start the fight trail-ambush', 'Start the fight trail-ambush by attacking raider', 'Start the fight toll-parley']);
    await press(harness, root, '[data-entry="engage:trail-ambush:raider"]');
    expect(text(root, '[data-entry-review-action]')).toBe('Start the fight trail-ambush by attacking raider');
    await press(harness, root, '[data-entry-cancel]');
    expect(root.querySelector('[data-entry-review]')).toBeNull();
    expect(server.commands).toEqual([]);
  });

  it('says so when nothing can be started here', /** An empty answer confirms nothing. */ async () => {
    server.encounterBody = encounterBody({ none: true });
    const { harness, root } = await open();
    await press(harness, root, '[data-look-for-fights]');
    await settle(harness, () => root.querySelector('[data-offers-none]') !== null);
    expect(text(root, '[data-offers-none]')).toBe('No fight can be started here right now.');
    expect(root.querySelector('[data-entry]')).toBeNull();
    expect(server.commands).toEqual([]);
  });

  it('offers no fight to start during one', /** Never out of turn. */ async () => {
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-combat]') !== null);
    expect(root.querySelector('[data-look-for-fights]')).toBeNull();
    expect(root.querySelector('[data-offers]')).toBeNull();
    expect(root.querySelector('[data-offers-none]')).toBeNull();
    expect(server.commands).toEqual([]);
  });

  it('offers no way to look for a fight while the playthrough is paused', /** Paused content cannot start one. */ async () => {
    server.resumeBody = resumeCard({ paused: true });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-paused]') !== null && root.querySelector('[data-panels]') !== null);
    expect(root.querySelector('[data-look-for-fights]')).toBeNull();
    expect(reads()).toBe(0);
  });

  it('sends one ENGAGE only after confirmation and retries a lost reply with the same identity', /** Never started twice. */ async () => {
    let lost = true;
    const committed = server.commandReply;
    server.commandReply = () => { if (lost) throw new TypeError('network'); return committed(); };
    const { harness, root } = await open();
    await press(harness, root, '[data-look-for-fights]');
    await settle(harness, () => root.querySelector('[data-entry]') !== null);
    await press(harness, root, '[data-entry="engage:trail-ambush:raider"]');
    expect(server.commands.length).toBe(0);
    await press(harness, root, '[data-entry-confirm]');
    await settle(harness, () => root.querySelector('[data-entry-problem]') !== null);
    expect(text(root, '[data-entry-problem]')).toBe('The reply was lost. If you repeat the action, it is played at most once.');
    const first = server.commands[0].body;
    expect(Object.keys(first).sort()).toEqual(['commandType', 'expectedRevision', 'payload', 'requestId']);
    expect([first['commandType'], first['expectedRevision']]).toEqual(['ENGAGE', 2]);
    expect(first['payload']).toEqual({ encounterId: 'trail-ambush', openingStrike: 'raider' });
    lost = false;
    await press(harness, root, '[data-entry-confirm]');
    await settle(harness, () => server.commands.length === 2 && root.querySelector('[data-offers]') === null);
    expect(server.commands[1].body).toEqual(first);
    // The accepted start re-reads the playthrough; nothing else is sent by itself.
    expect(server.commands.length).toBe(2);
  });
});
