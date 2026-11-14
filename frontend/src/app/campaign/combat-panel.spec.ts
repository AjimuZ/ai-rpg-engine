/** Combat panel (P12.1-B): strict parsing, exhaustive wording, rendering of every projection field and read-only behavior. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import {
  CampaignProtocolError, COMBAT_OBJECTIVE_STATUSES, COMBAT_OUTCOMES, COMBAT_PHASES, COMBAT_SIDES, COMBAT_STATUSES, parseCombatView, parseResumeCard,
} from './campaign-protocol';
import {
  coverMessage, disabledMessage, objectiveStatusMessage, phaseMessage, reactionPolicyMessage, sideMessage, standingMessage, verdictMessage,
} from './combat-messages';
import { ADVENTURE, CAMPAIGN, CampaignServer, encounterBody, MemoryStore, resumeCard, syncProvider } from './fixtures';

/** Wait until the routed screen satisfies a predicate. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

/** Clone the fight body and change it, so a spec can break exactly one field. */
function mutate(change: (body: Record<string, any>) => void, options: { verdict?: boolean } = {}): unknown {
  const body = JSON.parse(JSON.stringify(encounterBody(options))) as Record<string, any>;
  change(body);
  return body;
}

describe('Combat parser', /** Every shape the server sends is checked; nothing partial reaches a screen. */ () => {
  it('reads a complete fight exactly as sent', /** Values are carried unchanged. */ () => {
    const view = parseCombatView(encounterBody(), ADVENTURE, CAMPAIGN);
    const fight = view.encounter!;
    expect(view.stateRevision).toBe(2);
    expect([fight.phase, fight.round, fight.roundCap, fight.acting, fight.actionPoints, fight.actionPointsPerActivation]).toEqual(['AWAITING_ACTION', 2, 8, 'player', 2, 3]);
    expect(fight.initiative.map((place) => place.participantId)).toEqual(['player', 'sentry', 'scout']);
    expect(fight.edges.find((edge) => edge.blocked)?.cost).toBeNull();
    expect(fight.participants.find((who) => who.participantId === 'sentry')?.known).toEqual({ health: 6, healthMax: 10, guard: 3, resistance: 1, armor: 2, round: 1, stateRevision: 2 });
    expect(fight.participants.find((who) => who.participantId === 'scout')?.known).toBeNull();
    expect(fight.options?.strikes.map((strike) => strike.disabled)).toEqual([null, 'NO_AMMUNITION']);
    expect(fight.options?.actions[0].terms).toEqual({ termsId: 'toll', kind: 'pay', cost: 1 });
    expect(fight.options?.reactions[0]).toEqual({ reactionId: 'parry', mode: 'healthAtMost', value: 50 });
    expect(fight.outcome).toBeNull();
  });

  it('reads a body outside a fight and a decided fight without options', /** Both are legal projections. */ () => {
    expect(parseCombatView(encounterBody({ none: true }), ADVENTURE, CAMPAIGN).encounter).toBeNull();
    const decided = parseCombatView(encounterBody({ verdict: true }), ADVENTURE, CAMPAIGN).encounter!;
    expect(decided.options).toBeNull();
    expect(decided.acting).toBeNull();
    expect(decided.outcome).toEqual({ outcome: 'VICTORY', objectiveId: null, termsId: null, roundCap: false, objectives: [{ objectiveId: 'clear', status: 'met' }] });
  });

  it.each<[string, (body: Record<string, any>) => void]>([
    ['a foreign campaign', (body) => { body['scope']['campaignId'] = '00000000-0000-4000-8000-0000000000ff'; }],
    ['an unknown phase', (body) => { body['encounter']['phase'] = 'DREAMING'; }],
    ['an unknown side', (body) => { body['encounter']['participants'][0]['side'] = 'neutral'; }],
    ['an unknown standing', (body) => { body['encounter']['participants'][0]['status'] = 'ASLEEP'; }],
    ['an unknown cover', (body) => { body['encounter']['zones'][0]['cover'] = 'total'; }],
    ['an unknown objective kind', (body) => { body['encounter']['objectives'][0]['kind'] = 'conquer'; }],
    ['an unknown reaction mode', (body) => { body['encounter']['options']['reactions'][0]['policy']['mode'] = 'always'; }],
    ['a lower-case disabled reason', (body) => { body['encounter']['options']['strikes'][1]['disabled'] = 'no ammunition left'; }],
    ['acting points without an acting participant', (body) => { body['encounter']['acting'] = null; }],
    ['a negative cost', (body) => { body['encounter']['options']['moves'][0]['cost'] = -1; }],
    ['a non-integer value', (body) => { body['encounter']['player']['health'] = 1.5; }],
    ['a blank identifier', (body) => { body['encounter']['participants'][1]['participantId'] = ' '; }],
    ['an oversized list', (body) => { body['encounter']['edges'] = Array.from({ length: 257 }, () => body['encounter']['edges'][0]); }],
    ['an unknown outcome', (body) => { body['encounter']['outcome'] = { outcome: 'DRAW', objectiveId: null, objectives: [] }; }],
    ['a non-object body', (body) => { for (const key of Object.keys(body)) delete body[key]; }],
  ])('refuses %s', /** One broken field rejects the whole body. */ (_name, change) => {
    expect(() => parseCombatView(mutate(change), ADVENTURE, CAMPAIGN)).toThrow(CampaignProtocolError);
  });

  it('marks a resume card with an encounter as in a fight', /** The page reads the projection only then. */ () => {
    const scope = { adventureId: ADVENTURE, campaignId: CAMPAIGN };
    expect(parseResumeCard(resumeCard({ fight: true }), scope.adventureId, scope.campaignId).inFight).toBe(true);
    expect(parseResumeCard(resumeCard(), scope.adventureId, scope.campaignId).inFight).toBe(false);
  });
});

describe('Combat wording', /** Every enumeration the server can send has its own sentence. */ () => {
  it('words every phase, side, standing, cover, status and outcome without showing a code', /** No enum value may render empty. */ () => {
    for (const phase of COMBAT_PHASES) {
      for (const decision of [true, false]) expect(phaseMessage(phase, decision).length, phase).toBeGreaterThan(8);
    }
    expect(phaseMessage('AWAITING_ACTION', true)).not.toBe(phaseMessage('AWAITING_ACTION', false));
    const words = [...COMBAT_SIDES.map(sideMessage), ...COMBAT_STATUSES.map(standingMessage), ...(['none', 'partial', 'full'] as const).map(coverMessage),
      ...COMBAT_OBJECTIVE_STATUSES.map(objectiveStatusMessage), ...COMBAT_OUTCOMES.map(verdictMessage)];
    expect(new Set(words).size).toBe(words.length);
    for (const word of words) expect(word).not.toMatch(/[A-Z]{3,}|_/);
  });

  it('words known disabled reasons and gives an unknown one the generic sentence', /** Server text never becomes UI text. */ () => {
    const generic = disabledMessage('SOMETHING_NEW');
    const known = ['NO_AMMUNITION', 'NO_TARGET_IN_REACH', 'ACTION_POINTS', 'PREVENTED', 'FOCUS', 'LIMIT', 'COOLDOWN'];
    for (const code of known) { expect(disabledMessage(code), code).not.toBe(generic); expect(disabledMessage(code)).not.toContain('_'); }
    expect(generic).not.toContain('SOMETHING');
    for (const code of ['__proto__', 'constructor', '<script>']) expect(disabledMessage(code)).toBe(generic);
    expect(reactionPolicyMessage({ reactionId: 'parry', mode: 'healthAtMost', value: 50 }, '50')).toBe('Used when Health is at most 50 percent');
  });
});

describe('Combat panel in the playthrough view', /** The routed view shows the server's projection and never submits anything. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server and router per test. */ () => {
    server = new CampaignServer();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  /** Open the playthrough and wait for the panels, which load after the combat projection. */
  async function open(): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-panels]') !== null);
    return root;
  }
  const text = (root: HTMLElement, selector: string) => (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('shows nothing and requests nothing outside a fight', /** No extra request for an exploration playthrough. */ async () => {
    const root = await open();
    expect(root.querySelector('[data-combat]')).toBeNull();
    expect(text(root, '[data-resume] dl')).toContain('None');
    expect(server.calls.some((url) => url.includes('/encounter?'))).toBe(false);
  });

  it('renders every projection field of a fight at the player\'s decision', /** Cursor, order, zones, participants, values, objectives and candidates. */ async () => {
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody();
    const root = await open();
    expect(server.calls.some((url) => url.includes('/encounter?'))).toBe(true);
    expect(text(root, '[data-encounter]')).toBe('In progress');
    expect(text(root, '[data-phase]')).toBe('Your turn: choose what to do.');
    expect(text(root, '[data-round]')).toBe('Round 2 of 8');
    expect(text(root, '[data-acting]')).toBe('Acting: You, 2 of 3 action points left');
    expect([...root.querySelectorAll('[data-initiative] li')].map((item) => item.getAttribute('data-participant'))).toEqual(['player', 'sentry', 'scout']);
    expect(root.querySelector('[data-initiative] li[aria-current="true"]')?.getAttribute('data-participant')).toBe('player');
    expect(text(root, '[data-initiative] li:nth-child(2)')).toBe('sentry · Initiative 9');
    // Zones carry cover, obscuring, capacity and who stands there; passages carry their state and price.
    expect(text(root, '[data-zone="yard"]')).toBe('yard · Partial cover · Obscured · Holds at most 2 · sentry');
    expect(text(root, '[data-zone="gate"]')).toContain('No occupant limit');
    expect(text(root, '[data-edge="gate>yard"]')).toContain('Open · Action points to cross: 1');
    expect(text(root, '[data-edge="yard>gate"]')).toContain('Difficult terrain · Action points to cross: 2');
    expect(text(root, '[data-edge="yard>tower"]')).toBe('yard → tower · Blocked');
    expect(text(root, '[data-escape]')).toBe('gate');
    // A participant's standing, effects and what was learned about it, with when.
    const sentry = text(root, '[data-participants] [data-participant="sentry"]');
    expect(sentry).toContain('Hostile · Active · yard');
    expect(sentry).toContain('dazed ×1');
    expect(sentry).toContain('Health 6 / 10 · Guard 3 · Resistance 1 · Armor 2.');
    expect(sentry).toContain('Learned in round 1 (revision 2). Health may have changed since.');
    expect(text(root, '[data-participants] [data-participant="scout"] [data-known]')).toBe('Known defenses: Not assessed yet.');
    expect(text(root, '[data-participants] [data-participant="player"]')).not.toContain('Known defenses');
    // The player's own values.
    expect([...['health', 'focus', 'guard', 'resistance', 'armor', 'guard-stance', 'reactions-left'].map((name) => text(root, `[data-${name}]`))])
      .toEqual(['14 / 20', '3 / 5', '4', '2', '1', 'Not held', '1']);
    expect(root.querySelector('[data-health] meter')?.getAttribute('aria-label')).toBe('Health');
    // Objectives with their status.
    expect(text(root, '[data-objective="clear"]')).toBe('Defeat every hostile participant · Pending');
    expect(text(root, '[data-objective="hold"]')).toBe('Survive 8 rounds · Pending');
    expect(text(root, '[data-objective="cover"]')).toBe('Protect scout · Pending');
    // Legal candidates with costs, targets and the stable reason a candidate is disabled.
    expect(text(root, '[data-moves]')).toBe('Move to yard · Action points: 1');
    expect(text(root, '[data-attack="blade"]')).toBe('Attack with blade · Action points: 2 · Targets: sentry');
    expect(text(root, '[data-attack="bow"]')).toBe('Attack with bow · Action points: 2 · No ammunition left');
    expect(root.querySelector('[data-attack="bow"]')?.getAttribute('data-disabled')).toBe('NO_AMMUNITION');
    expect(root.querySelector('[data-attack="blade"]')?.hasAttribute('data-disabled')).toBe(false);
    expect(text(root, '[data-ability="rally"]')).toBe('Use the ability rally · Action points: 1 · Focus: 2 · Targets: You · Targets at most: 1');
    expect(text(root, '[data-ability="surge"]')).toBe('Use the ability surge · Action points: 3 · Focus: 9 · Not enough Focus');
    expect(text(root, '[data-action="plead"]')).toBe('Social action plead · Action points: 1 · Targets: sentry · Check on presence, rank 1 · Terms toll (pay), cost 1');
    expect(text(root, '[data-guard-option]')).toBe('Take a guarded stance · Action points: 1');
    expect(text(root, '[data-item-option]')).toBe('Use an item · Action points: 1 · Not available now');
    expect(text(root, '[data-ready-option]')).toBe('Ready or change equipment · Action points: 1');
    expect(text(root, '[data-assess-option]')).toBe('Assess a participant · Action points: 1 · Targets: sentry');
    expect(text(root, '[data-withdraw="gate"]')).toBe('Withdraw to gate · Action points: 1');
    expect(text(root, '[data-speech-option]')).toBe('Speak: 1 left this turn · Recipients: sentry');
    expect(text(root, '[data-reaction="parry"]')).toBe('parry · Used when Health is at most 50 percent');
    expect(root.querySelector('[data-end-option]')).not.toBeNull();
    expect(root.querySelector('[data-verdict]')).toBeNull();
  });

  it('shows the verdict and no candidates once the fight is decided', /** Nothing is offered at the settlement point except the verdict. */ async () => {
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody({ verdict: true });
    const root = await open();
    expect(text(root, '[data-phase]')).toBe('The fight is decided and waits for you to settle it.');
    expect(root.querySelector('[data-acting]')).toBeNull();
    expect(text(root, '[data-verdict-kind]')).toBe('Victory');
    expect(text(root, '[data-verdict]')).toContain('Nothing more changes until you settle it.');
    expect(root.querySelector('[data-no-decision]')).not.toBeNull();
    expect(root.querySelector('[data-moves]')).toBeNull();
    expect(root.querySelector('[data-attack]')).toBeNull();
    expect(text(root, '[data-objective="clear"]')).toContain('Met');
  });

  it('is operable by keyboard and sends no command by itself', /** Native details and one focusable scroll region; only GETs are issued. */ async () => {
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody();
    const root = await open();
    const sections = [...root.querySelectorAll('[data-combat] details')];
    expect(sections.length).toBe(6);
    for (const section of sections) {
      expect(section.querySelector('summary')?.textContent?.trim().length).toBeGreaterThan(2);
      expect(section.hasAttribute('open')).toBe(true);
    }
    const region = root.querySelector('[data-zones] [role="region"]')!;
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.getAttribute('aria-label')).toBe('Zones and passages, scrollable');
    expect(root.querySelector('[data-combat]')!.getAttribute('aria-labelledby')).toBe('combat-title');
    // Since P12.2-A the panel has buttons that only open a review; none is a submit button and nothing was sent by rendering.
    // The review and confirmation behavior is in combat-actions.spec.ts.
    const buttons = [...root.querySelectorAll('[data-combat] button')];
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons) expect(button.getAttribute('type')).toBe('button');
    expect(root.querySelector('[data-confirm]')).toBeNull();
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
  });

  it('reports a malformed projection as the usual localized error and renders no panel', /** Nothing partial is shown. */ async () => {
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = { code: 'CAMPAIGN_SCOPE_UNAVAILABLE' };
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-error]') !== null);
    expect(root.querySelector('[data-combat]')).toBeNull();
  });
});
