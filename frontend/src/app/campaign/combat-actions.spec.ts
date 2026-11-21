/**
 * Named tactical actions (P12.2-A): validation against the projection, cost previews, explicit confirmation, and that out-of-turn,
 * illegal or refused actions change nothing and send nothing by themselves.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { CombatEncounter, parseCombatView } from './campaign-protocol';
import { choiceKey, resolveChoice, TACTICAL_REFUSALS, tacticalEntries } from './combat-actions';
import { refusalMessage } from './combat-messages';
import { ADVENTURE, CAMPAIGN, CampaignServer, encounterBody, json, MemoryStore, resumeCard, syncProvider } from './fixtures';

/** The fixture fight parsed as the page parses it, optionally changed first. */
function fight(change: (body: Record<string, any>) => void = () => undefined, verdict = false): CombatEncounter {
  const body = JSON.parse(JSON.stringify(encounterBody({ verdict }))) as Record<string, any>;
  change(body);
  return parseCombatView(body, ADVENTURE, CAMPAIGN).encounter!;
}

describe('Tactical choice validation', /** A choice that the projection does not allow builds no command. */ () => {
  it('lists one named entry per action and target and keeps disabled ones visible', /** Reasons stay readable. */ () => {
    const entries = tacticalEntries(fight());
    expect(entries.map((entry) => entry.key)).toEqual(['move:yard', 'strike:blade:sentry', 'ability:rally:player', 'ability:surge:',
      'interact:plead:sentry', 'guard', 'end']);
    expect(entries.find((entry) => entry.key === 'ability:surge:')?.disabled).toBe('FOCUS');
    expect(entries.find((entry) => entry.key === 'strike:blade:sentry')?.disabled).toBeNull();
  });

  it('builds the closed payload of each command type with its preview', /** Payload members are exactly the server's. */ () => {
    const built = (choice: Parameters<typeof resolveChoice>[1]) => {
      const resolved = resolveChoice(fight(), choice);
      if (!resolved.ok) throw new Error(resolved.refusal);
      return resolved;
    };
    expect(built({ kind: 'move', zone: 'yard' }).command).toEqual({ commandType: 'MOVE_ZONE', payload: { zoneId: 'yard' } });
    expect(built({ kind: 'strike', attackId: 'blade', targetId: 'sentry' }).command).toEqual({ commandType: 'STRIKE', payload: { attackId: 'blade', targetId: 'sentry' } });
    expect(built({ kind: 'ability', abilityId: 'rally', targetIds: ['player'] }).command).toEqual({ commandType: 'COMBAT_USE_ABILITY', payload: { abilityId: 'rally', targetIds: ['player'] } });
    expect(built({ kind: 'interact', actionId: 'plead', targetId: 'sentry' }).command).toEqual({ commandType: 'INTERACT', payload: { actionId: 'plead', targetId: 'sentry' } });
    expect(built({ kind: 'guard' }).command).toEqual({ commandType: 'GUARD', payload: {} });
    expect(built({ kind: 'end' }).command).toEqual({ commandType: 'END_ACTIVATION', payload: {} });
    const strike = built({ kind: 'strike', attackId: 'blade', targetId: 'sentry' }).preview;
    expect([strike.points, strike.pointsAfter, strike.focus, strike.focusAfter, strike.endsTurn]).toEqual([2, 0, 0, 3, false]);
    const rally = built({ kind: 'ability', abilityId: 'rally', targetIds: ['player'] }).preview;
    expect([rally.points, rally.pointsAfter, rally.focus, rally.focusAfter]).toEqual([1, 1, 2, 1]);
    const plead = built({ kind: 'interact', actionId: 'plead', targetId: 'sentry' }).preview;
    expect(plead.check).toEqual({ attribute: 'presence', skill: null, rank: 1 });
    expect(plead.terms?.termsId).toBe('toll');
    const end = built({ kind: 'end' }).preview;
    expect([end.points, end.pointsAfter, end.endsTurn]).toEqual([2, 0, true]);
  });

  it('refuses everything outside the player\'s own decision', /** Out of turn changes nothing. */ () => {
    const decided = fight(() => undefined, true);
    for (const choice of [{ kind: 'end' }, { kind: 'guard' }, { kind: 'move', zone: 'yard' }] as const) {
      expect(resolveChoice(decided, choice)).toEqual({ ok: false, refusal: 'NOT_YOUR_TURN' });
      expect(resolveChoice(null, choice)).toEqual({ ok: false, refusal: 'NOT_YOUR_TURN' });
    }
    // At the verdict the only legal choice is the explicit settlement (P12.5-B); it is never a tactical choice.
    expect(tacticalEntries(decided).map((entry) => entry.key)).toEqual(['settle']);
    const waiting = fight((body) => { body['encounter']['playerDecision'] = false; body['encounter']['acting'] = 'sentry'; });
    expect(tacticalEntries(waiting)).toEqual([]);
    expect(resolveChoice(waiting, { kind: 'end' })).toEqual({ ok: false, refusal: 'NOT_YOUR_TURN' });
  });

  it('refuses choices the projection does not list, disables or cannot pay for', /** Illegal changes nothing. */ () => {
    const now = fight();
    const refused = (choice: Parameters<typeof resolveChoice>[1]) => { const r = resolveChoice(now, choice); return r.ok ? 'OK' : r.refusal; };
    expect(refused({ kind: 'move', zone: 'tower' })).toBe('NOT_LISTED');
    expect(refused({ kind: 'strike', attackId: 'blade', targetId: 'scout' })).toBe('NOT_LISTED');
    expect(refused({ kind: 'strike', attackId: 'axe', targetId: 'sentry' })).toBe('NOT_LISTED');
    expect(refused({ kind: 'ability', abilityId: 'rally', targetIds: [] })).toBe('NOT_LISTED');
    expect(refused({ kind: 'ability', abilityId: 'rally', targetIds: ['player', 'player'] })).toBe('NOT_LISTED');
    expect(refused({ kind: 'ability', abilityId: 'surge', targetIds: [] })).toBe('DISABLED');
    expect(refused({ kind: 'interact', actionId: 'plead', targetId: null })).toBe('NOT_LISTED');
    expect(refused({ kind: 'interact', actionId: 'plead', targetId: 'scout' })).toBe('NOT_LISTED');
    // Costs: two points remain, so a three-point strike is refused before any request; Focus 3 cannot pay 4.
    const dear = fight((body) => { body['encounter']['options']['strikes'][0]['cost'] = 3; });
    expect(resolveChoice(dear, { kind: 'strike', attackId: 'blade', targetId: 'sentry' })).toEqual({ ok: false, refusal: 'ACTION_POINTS' });
    const spent = fight((body) => { body['encounter']['options']['abilities'][0]['focus'] = 4; });
    expect(resolveChoice(spent, { kind: 'ability', abilityId: 'rally', targetIds: ['player'] })).toEqual({ ok: false, refusal: 'FOCUS' });
    const noGuard = fight((body) => { body['encounter']['options']['guard'] = { cost: 1, available: false }; });
    expect(resolveChoice(noGuard, { kind: 'guard' })).toEqual({ ok: false, refusal: 'DISABLED' });
    const noEnd = fight((body) => { body['encounter']['options']['endActivation'] = false; });
    expect(resolveChoice(noEnd, { kind: 'end' })).toEqual({ ok: false, refusal: 'NOT_LISTED' });
  });

  it('offers an optional module\'s ability only when the projection lists it', /** A disabled module leaves no choice (P15.5-A). */ () => {
    const plain = tacticalEntries(fight());
    expect(plain.some((entry) => entry.key.includes('#'))).toBe(false);
    const learned = fight((body) => { body['encounter']['options']['abilities'].push(
      { abilityId: '#recipe:gust', cost: 2, focus: 2, targets: ['sentry'], maxTargets: 1, disabled: null }); });
    expect(tacticalEntries(learned).map((entry) => entry.key)).toContain('ability:#recipe:gust:sentry');
    const recipe = resolveChoice(learned, { kind: 'ability', abilityId: '#recipe:gust', targetIds: ['sentry'] });
    expect(recipe.ok && recipe.command).toEqual({ commandType: 'COMBAT_USE_ABILITY', payload: { abilityId: '#recipe:gust', targetIds: ['sentry'] } });
    // No listed choice becomes a draft or learning command: the page has no path to either.
    for (const current of [fight(), learned]) {
      for (const entry of tacticalEntries(current)) {
        const resolved = resolveChoice(current, entry.choice);
        if (resolved.ok) expect(['COMBAT_USE_DRAFT', 'LEARN_DRAFT']).not.toContain(resolved.command.commandType);
      }
    }
  });

  it('words every refusal, local or from the server, without showing a code', /** Nothing partial reaches the player. */ () => {
    const generic = refusalMessage('COMMAND_SOMETHING_NEW');
    for (const code of [...TACTICAL_REFUSALS, 'COMMAND_TURN_UNAVAILABLE', 'COMMAND_ACTION_POINTS_INSUFFICIENT', 'COMMAND_STALE_REVISION', 'COMMAND_CAMPAIGN_BUSY', 'LOST']) {
      expect(refusalMessage(code), code).toMatch(/\.$/);
      expect(refusalMessage(code)).not.toContain('_');
    }
    expect(generic).not.toContain('SOMETHING');
    expect(choiceKey({ kind: 'ability', abilityId: 'rally', targetIds: ['player'] })).toBe('ability:rally:player');
  });
});

describe('Named actions in the playthrough view', /** Review first, send only on confirmation. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server and router per test, in a fight. */ () => {
    server = new CampaignServer();
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody();
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
    await settle(harness, () => root.querySelector('[data-send-list] button') !== null);
    return { harness, root };
  }
  const text = (root: HTMLElement, selector: string) => (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  async function press(harness: RouterTestingHarness, root: HTMLElement, selector: string): Promise<void> {
    (root.querySelector(selector) as HTMLButtonElement).click();
    await new Promise((accept) => setTimeout(accept, 0));
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  }

  it('previews the costs of a chosen action and sends nothing', /** Choosing is not submitting. */ async () => {
    const { harness, root } = await open();
    expect(text(root, '[data-choice="strike:blade:sentry"]')).toBe('Attack with blade · Targets: sentry');
    expect(root.querySelector('[data-review]')).toBeNull();
    await press(harness, root, '[data-choice="ability:rally:player"]');
    expect(text(root, '[data-review-action]')).toBe('Use the ability rally · Targets: You');
    expect(text(root, '[data-review-points]')).toBe('Spends 1 action points; 1 left afterwards.');
    expect(text(root, '[data-review-focus]')).toBe('Spends 2 Focus; 1 left afterwards.');
    expect(root.querySelector('[data-choice="ability:rally:player"]')?.getAttribute('aria-pressed')).toBe('true');
    await press(harness, root, '[data-choice="end"]');
    expect(text(root, '[data-review-points]')).toBe('Ends your turn. Unused action points that are lost: 2. The others act next.');
    expect(root.querySelector('[data-review-focus]')).toBeNull();
    await press(harness, root, '[data-choice="interact:plead:sentry"]');
    expect(text(root, '[data-review-check]')).toBe('Check on presence, rank 1');
    expect(text(root, '[data-review-terms]')).toBe('Terms toll (pay), cost 1');
    await press(harness, root, '[data-cancel]');
    expect(root.querySelector('[data-review]')).toBeNull();
    expect(server.commands).toEqual([]);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
  });

  it('keeps unavailable actions disabled with their reason', /** A disabled choice cannot even be reviewed. */ async () => {
    const { harness, root } = await open();
    const surge = root.querySelector('[data-choice="ability:surge:"]') as HTMLButtonElement;
    expect(surge.disabled).toBe(true);
    expect(text(root, '[data-send-list]')).toContain('Not enough Focus');
    surge.click();
    harness.fixture.detectChanges();
    expect(root.querySelector('[data-review]')).toBeNull();
    expect(server.commands).toEqual([]);
  });

  it('sends exactly one closed command only after confirmation, then re-reads the state', /** The envelope names the shown revision. */ async () => {
    const { harness, root } = await open();
    const stateReads = () => server.calls.filter((url) => url.includes('/encounter?')).length;
    const before = stateReads();
    await press(harness, root, '[data-choice="strike:blade:sentry"]');
    expect(server.commands.length).toBe(0);
    await press(harness, root, '[data-confirm]');
    await settle(harness, () => server.commands.length === 1 && stateReads() > before);
    const sent = server.commands[0];
    expect(sent.csrf).toBe('token');
    expect(Object.keys(sent.body).sort()).toEqual(['commandType', 'expectedRevision', 'payload', 'requestId']);
    expect(sent.body['requestId']).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(sent.body['expectedRevision']).toBe(2);
    expect(sent.body['commandType']).toBe('STRIKE');
    expect(sent.body['payload']).toEqual({ attackId: 'blade', targetId: 'sentry' });
    expect(server.commands.length).toBe(1);
  });

  it('shows a server refusal as a sentence, changes nothing and leaves the player able to try again', /** 422 leaves no operation. */ async () => {
    server.commandReply = () => json({ code: 'COMMAND_TURN_UNAVAILABLE' }, 422);
    const { harness, root } = await open();
    await press(harness, root, '[data-choice="guard"]');
    await press(harness, root, '[data-confirm]');
    await settle(harness, () => root.querySelector('[data-problem]') !== null);
    expect(text(root, '[data-problem]')).toBe('It is not your turn to act. Nothing changed.');
    expect(root.querySelector('[data-problem]')?.textContent).not.toContain('COMMAND');
    expect(root.querySelector('[data-operation-id]')).toBeNull();
    expect(root.querySelector('[data-confirm]')).not.toBeNull();
    expect((root.querySelector('[data-confirm]') as HTMLButtonElement).disabled).toBe(false);
    // Looking at another action clears the notice; nothing else was sent.
    await press(harness, root, '[data-choice="end"]');
    expect(root.querySelector('[data-problem]')).toBeNull();
    expect(server.commands.length).toBe(1);
  });

  it('repeats a lost submission with the same request identity', /** A retry cannot play the action twice. */ async () => {
    let lost = true;
    server.commandReply = () => { if (lost) throw new TypeError('network'); return json({ operationId: '00000000-0000-4000-8000-0000000000c1', campaignId: CAMPAIGN,
      commandType: 'GUARD', status: 'COMMITTED', baseRevision: 2, committedRevision: 3, operationVersion: 1, replayed: true, receipt: null }, 200); };
    const { harness, root } = await open();
    await press(harness, root, '[data-choice="guard"]');
    await press(harness, root, '[data-confirm]');
    await settle(harness, () => root.querySelector('[data-problem]') !== null);
    expect(text(root, '[data-problem]')).toBe('The reply was lost. If you repeat the action, it is played at most once.');
    lost = false;
    await press(harness, root, '[data-choice="guard"]');
    await press(harness, root, '[data-confirm]');
    await settle(harness, () => server.commands.length === 2);
    expect(server.commands[1].body['requestId']).toBe(server.commands[0].body['requestId']);
  });

  it('offers only the explicit settlement once the fight is decided, and sends nothing by itself', /** No tactical entry, no request. */ async () => {
    server.encounterBody = encounterBody({ verdict: true });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-send-list] button') !== null);
    expect([...root.querySelectorAll('[data-send-list] button')].map((button) => button.getAttribute('data-choice'))).toEqual(['settle']);
    expect(root.querySelector('[data-none-to-send]')).toBeNull();
    expect(server.commands).toEqual([]);
  });
});
