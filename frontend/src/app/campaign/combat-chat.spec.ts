/**
 * Combat chat (P12.2-B): speech and offer-answer validation against the projection, the public transcript, and the chat's review
 * before anything is sent. Writing, choosing a recipient and filling a quick reply never send, and a refusal keeps the draft.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { CombatEncounter, JournalEntry, parseCombatView } from './campaign-protocol';
import { resolveChoice, tacticalEntries } from './combat-actions';
import { transcript } from './combat-chat';
import { ADVENTURE, CAMPAIGN, CampaignServer, encounterBody, json, MemoryStore, operation, OP1, panelsBody, resumeCard, syncProvider } from './fixtures';

/** The fixture fight parsed as the page parses it, optionally changed first. */
function fight(change: (body: Record<string, any>) => void = () => undefined, offer = false): CombatEncounter {
  const body = JSON.parse(JSON.stringify(encounterBody({ offer }))) as Record<string, any>;
  change(body);
  return parseCombatView(body, ADVENTURE, CAMPAIGN).encounter!;
}

const entry = (sequence: number, type: string, statement = `line ${sequence}`): JournalEntry =>
  ({ sequence, type, stateRevision: 2, clock: { seconds: 0, day: 1, hour: 0, minute: 0, second: 0 }, statement } as JournalEntry);

describe('Combat chat rules', /** Speech and answers are validated like every other named action. */ () => {
  it('builds the closed speech command with the exact words and shows that it costs no action points', /** Speech-only. */ () => {
    const words = '  Let us talk.  ';
    const resolved = resolveChoice(fight(), { kind: 'speak', recipientId: 'sentry', text: words });
    if (!resolved.ok) throw new Error(resolved.refusal);
    expect(resolved.command).toEqual({ commandType: 'COMBAT_SPEAK', payload: { recipientId: 'sentry', text: words } });
    expect([resolved.preview.points, resolved.preview.pointsAfter, resolved.preview.endsTurn]).toEqual([0, 2, false]);
    expect(resolved.preview.speech).toEqual({ recipientId: 'sentry', text: words, remainingAfter: 0 });
  });

  it('refuses speech that is ambiguous, out of reach, spent, unreadable or out of turn, building nothing', /** No guessing. */ () => {
    const now = fight();
    const refused = (recipientId: string, text: string, from: CombatEncounter = now) => {
      const result = resolveChoice(from, { kind: 'speak', recipientId, text });
      return result.ok ? 'OK' : result.refusal;
    };
    expect(refused('', 'Hello')).toBe('NO_RECIPIENT');
    expect(refused('scout', 'Hello')).toBe('NOT_ADDRESSABLE');
    expect(refused('nobody', 'Hello')).toBe('NOT_ADDRESSABLE');
    expect(refused('sentry', '   ')).toBe('EMPTY_TEXT');
    expect(refused('sentry', 'x'.repeat(501))).toBe('TEXT_TOO_LONG');
    expect(refused('sentry', 'x'.repeat(500))).toBe('OK');
    expect(refused('sentry', 'broken \uD800 text')).toBe('TEXT_INVALID');
    expect(refused('sentry', 'nul \u0000')).toBe('TEXT_INVALID');
    expect(refused('sentry', 'Hello', fight((body) => { body['encounter']['options']['speech']['remaining'] = 0; }))).toBe('SPEECH_LIMIT');
    expect(refused('sentry', 'Hello', fight((body) => { body['encounter']['playerDecision'] = false; body['encounter']['acting'] = 'sentry'; }))).toBe('NOT_YOUR_TURN');
  });

  it('answers only the offer on the table, and accepting is possible only when the projection allows it', /** Explicit decisions. */ () => {
    expect(tacticalEntries(fight()).some((e) => e.choice.kind === 'answer')).toBe(false);
    expect(resolveChoice(fight(), { kind: 'answer', decision: 'accept' })).toEqual({ ok: false, refusal: 'NOT_LISTED' });
    const withOffer = fight(() => undefined, true);
    expect(tacticalEntries(withOffer).filter((e) => e.choice.kind === 'answer').map((e) => [e.key, e.disabled])).toEqual([['answer:accept', null], ['answer:decline', null]]);
    const accept = resolveChoice(withOffer, { kind: 'answer', decision: 'accept' });
    if (!accept.ok) throw new Error(accept.refusal);
    expect(accept.command).toEqual({ commandType: 'ANSWER_OFFER', payload: { offerId: 'offer-1', decision: 'accept' } });
    expect([accept.preview.points, accept.preview.pointsAfter, accept.preview.decision, accept.preview.terms?.termsId]).toEqual([1, 1, 'accept', 'toll']);
    const decline = resolveChoice(withOffer, { kind: 'answer', decision: 'decline' });
    if (!decline.ok) throw new Error(decline.refusal);
    expect([decline.preview.points, decline.preview.pointsAfter, decline.preview.decision]).toEqual([0, 2, 'decline']);
    const closed = fight((body) => { body['encounter']['options']['offer']['canAccept'] = false; }, true);
    expect(resolveChoice(closed, { kind: 'answer', decision: 'accept' })).toEqual({ ok: false, refusal: 'DISABLED' });
    expect(resolveChoice(closed, { kind: 'answer', decision: 'decline' }).ok).toBe(true);
    const dear = fight((body) => { body['encounter']['options']['offer']['terms']['cost'] = 3; }, true);
    expect(resolveChoice(dear, { kind: 'answer', decision: 'accept' })).toEqual({ ok: false, refusal: 'ACTION_POINTS' });
  });

  it('reads the newest fight\'s conversation from the journal, oldest first, and leaves other events out', /** Server wording only. */ () => {
    const journal = [entry(9, 'NEGOTIATION_RESPONDED'), entry(8, 'STRIKE_RESOLVED'), entry(7, 'COMBAT_SPEECH'), entry(6, 'ENCOUNTER_STARTED'),
      entry(5, 'COMBAT_SPEECH'), entry(4, 'OFFER_ANSWERED')];
    expect(transcript(journal).map((line) => [line.sequence, line.kind])).toEqual([[7, 'said'], [9, 'reply']]);
    expect(transcript(null)).toEqual([]);
    const long = Array.from({ length: 60 }, (_, index) => entry(index + 1, 'COMBAT_SPEECH'));
    expect(transcript(long).length).toBe(40);
    expect(transcript(long)[39].sequence).toBe(60);
  });
});

describe('Combat chat in the playthrough view', /** Draft first, review next, send only on confirmation. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server and router per test, in a fight with a short conversation. */ () => {
    server = new CampaignServer();
    server.resumeBody = resumeCard({ fight: true });
    server.encounterBody = encounterBody();
    const panels = JSON.parse(JSON.stringify(panelsBody())) as Record<string, any>;
    panels['journal']['entries'] = [
      { sequence: 12, type: 'NEGOTIATION_RESPONDED', stateRevision: 2, clock: panels['journal']['entries'][0]['clock'], statement: 'sentry refuses plead: the fight goes on.' },
      { sequence: 11, type: 'COMBAT_SPEECH', stateRevision: 2, clock: panels['journal']['entries'][0]['clock'], statement: 'Mara says to sentry: "Stand down."' }];
    server.panelsBody = panels;
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
    await settle(harness, () => root.querySelector('[data-chat-lines] li') !== null);
    return { harness, root };
  }
  const text = (root: HTMLElement, selector: string) => (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  async function tick(harness: RouterTestingHarness): Promise<void> {
    await new Promise((accept) => setTimeout(accept, 0));
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  }
  async function press(harness: RouterTestingHarness, root: HTMLElement, selector: string): Promise<void> {
    (root.querySelector(selector) as HTMLButtonElement).click();
    await tick(harness);
  }
  async function choose(harness: RouterTestingHarness, root: HTMLElement, who: string): Promise<void> {
    const select = root.querySelector('#combat-chat-recipient') as HTMLSelectElement;
    select.value = who;
    select.dispatchEvent(new Event('change'));
    await tick(harness);
  }
  async function write(harness: RouterTestingHarness, root: HTMLElement, words: string): Promise<void> {
    const field = root.querySelector('#combat-chat-words') as HTMLTextAreaElement;
    field.value = words;
    field.dispatchEvent(new Event('input'));
    await tick(harness);
  }
  const words = (root: HTMLElement) => (root.querySelector('#combat-chat-words') as HTMLTextAreaElement).value;

  it('shows the attributed conversation with a text label for each line and the known recipients', /** Not by color alone. */ async () => {
    const { root } = await open();
    const lines = [...root.querySelectorAll('[data-chat-lines] li')].map((li) => (li.textContent ?? '').replace(/\s+/g, ' ').trim());
    expect(lines).toEqual(['Said · Mara says to sentry: "Stand down."', 'Reply · sentry refuses plead: the fight goes on.']);
    expect([...root.querySelectorAll('#combat-chat-recipient option')].map((o) => (o.textContent ?? '').trim()))
      .toEqual(['Nobody chosen', 'sentry · Hostile', 'scout · Ally']);
    expect(server.commands).toEqual([]);
  });

  it('never invents a recipient and says why the words cannot be reviewed yet', /** Ambiguous text is not sent. */ async () => {
    const { harness, root } = await open();
    await write(harness, root, 'Hello there');
    expect((root.querySelector('[data-chat-review]') as HTMLButtonElement).disabled).toBe(true);
    expect(text(root, '[data-chat-block]')).toBe('Choose someone who can hear you now.');
    expect(text(root, '[data-chat-count]')).toBe('11 of 500 characters');
    await choose(harness, root, 'scout');
    expect((root.querySelector('[data-chat-review]') as HTMLButtonElement).disabled).toBe(true);
    await choose(harness, root, 'sentry');
    expect((root.querySelector('[data-chat-review]') as HTMLButtonElement).disabled).toBe(false);
    expect(text(root, '[data-chat-block]')).toBe('');
    expect(server.commands).toEqual([]);
  });

  it('fills a quick reply into an empty draft, and replaces a written draft only on request', /** Suggestions never overwrite. */ async () => {
    const { harness, root } = await open();
    expect(root.querySelector('[data-chat-quick] button')).toBeNull();
    await choose(harness, root, 'sentry');
    const quick = [...root.querySelectorAll('[data-chat-quick] button')].map((b) => (b.textContent ?? '').trim());
    expect(quick).toEqual(['Why are you attacking us?', "Let's negotiate.", 'Is there another way to settle this?']);
    (root.querySelectorAll('[data-chat-quick] button')[1] as HTMLButtonElement).click();
    await tick(harness);
    expect(words(root)).toBe("Let's negotiate.");
    await write(harness, root, "Let's negotiate. Please.");
    (root.querySelectorAll('[data-chat-quick] button')[0] as HTMLButtonElement).click();
    await tick(harness);
    expect(words(root)).toBe("Let's negotiate. Please.");
    expect(text(root, '[data-chat-replace]')).toContain('Replace your words with “Why are you attacking us?”?');
    await press(harness, root, '[data-chat-replace-no]');
    expect(words(root)).toBe("Let's negotiate. Please.");
    (root.querySelectorAll('[data-chat-quick] button')[0] as HTMLButtonElement).click();
    await tick(harness);
    await press(harness, root, '[data-chat-replace-yes]');
    expect(words(root)).toBe('Why are you attacking us?');
    expect(server.commands).toEqual([]);
  });

  it('previews speech with its cost, sends exactly one command on confirmation and clears the draft once it is committed', /** Review first. */ async () => {
    server.commandReply = () => {
      const next = JSON.parse(JSON.stringify(encounterBody())) as Record<string, any>;
      next['scope']['stateRevision'] = 3;
      server.encounterBody = next;
      return json(operation(OP1, 'COMMITTED', 1, true), 202);
    };
    const { harness, root } = await open();
    await choose(harness, root, 'sentry');
    await write(harness, root, 'Stand down, please.');
    await press(harness, root, '[data-chat-review]');
    expect(text(root, '[data-chat-review-action]')).toBe('Speak to sentry');
    expect(text(root, '[data-chat-review-words]')).toBe('Your words: “Stand down, please.”');
    expect(text(root, '[data-chat-review-cost]')).toBe('Speaks to sentry. No action points are spent; speeches left this turn afterwards: 0.');
    expect(server.commands.length).toBe(0);
    await press(harness, root, '[data-chat-confirm]');
    await settle(harness, () => server.commands.length === 1 && words(root) === '');
    expect(server.commands[0].body['commandType']).toBe('COMBAT_SPEAK');
    expect(server.commands[0].body['payload']).toEqual({ recipientId: 'sentry', text: 'Stand down, please.' });
    expect(server.commands[0].body['expectedRevision']).toBe(2);
    expect(server.commands.length).toBe(1);
  });

  it('keeps the draft and says nothing changed when the server refuses the words', /** Rejection retains the draft. */ async () => {
    server.commandReply = () => json({ code: 'COMMAND_SPEECH_LIMIT_REACHED' }, 422);
    const { harness, root } = await open();
    await choose(harness, root, 'sentry');
    await write(harness, root, 'One more thing.');
    await press(harness, root, '[data-chat-review]');
    await press(harness, root, '[data-chat-confirm]');
    await settle(harness, () => root.querySelector('[data-chat-problem]') !== null);
    expect(text(root, '[data-chat-problem]')).toBe('You have already spoken this turn. Nothing changed.');
    expect(words(root)).toBe('One more thing.');
    expect((root.querySelector('[data-chat-confirm]') as HTMLButtonElement).disabled).toBe(false);
    expect(server.commands.length).toBe(1);
  });

  it('keeps writing available outside the player\'s turn, explains why review is unavailable and queues nothing', /** No automatic submission. */ async () => {
    const body = JSON.parse(JSON.stringify(encounterBody())) as Record<string, any>;
    body['encounter']['playerDecision'] = false;
    body['encounter']['acting'] = 'sentry';
    body['encounter']['options'] = {};
    server.encounterBody = body;
    const { harness, root } = await open();
    await choose(harness, root, 'sentry');
    await write(harness, root, 'I am waiting.');
    expect(words(root)).toBe('I am waiting.');
    expect((root.querySelector('[data-chat-review]') as HTMLButtonElement).disabled).toBe(true);
    expect(text(root, '[data-chat-block]')).toBe('It is not your turn. You can keep writing; nothing is queued or sent for you.');
    expect(server.commands).toEqual([]);
  });

  it('lists social actions only for the chosen recipient and reviews one with its check and terms before sending', /** Declared costs first. */ async () => {
    const { harness, root } = await open();
    expect(root.querySelector('[data-chat-intents] button')).toBeNull();
    expect(root.querySelector('[data-chat-intents-none]')).not.toBeNull();
    await choose(harness, root, 'scout');
    expect(root.querySelector('[data-chat-intents] button')).toBeNull();
    await choose(harness, root, 'sentry');
    expect(text(root, '[data-chat-choice="interact:plead:sentry"]')).toBe('Social action plead · Targets: sentry');
    await press(harness, root, '[data-chat-choice="interact:plead:sentry"]');
    expect(text(root, '[data-chat-review-points]')).toBe('Spends 1 action points; 1 left afterwards.');
    expect(text(root, '[data-chat-review-check]')).toBe('Check on presence, rank 1');
    expect(text(root, '[data-chat-review-terms]')).toBe('Terms toll (pay), cost 1');
    expect(server.commands).toEqual([]);
    await press(harness, root, '[data-chat-confirm]');
    await settle(harness, () => server.commands.length === 1);
    expect(server.commands[0].body['commandType']).toBe('INTERACT');
    expect(server.commands[0].body['payload']).toEqual({ actionId: 'plead', targetId: 'sentry' });
  });

  it('answers an offer only through an explicit, previewed decision', /** The offer binds nobody until answered. */ async () => {
    server.encounterBody = encounterBody({ offer: true });
    const { harness, root } = await open();
    expect(text(root, '[data-chat-offer]')).toContain('Terms toll are on the table with sentry.');
    expect(server.commands).toEqual([]);
    await press(harness, root, '[data-chat-choice="answer:decline"]');
    expect(text(root, '[data-chat-review-binding]')).toBe('Declining costs nothing and leaves the fight as it is.');
    await press(harness, root, '[data-chat-choice="answer:accept"]');
    expect(text(root, '[data-chat-review-action]')).toBe('Accept the terms toll');
    expect(text(root, '[data-chat-review-binding]')).toBe('Accepting binds you to these terms and cannot be taken back.');
    expect(server.commands.length).toBe(0);
    await press(harness, root, '[data-chat-confirm]');
    await settle(harness, () => server.commands.length === 1);
    expect(server.commands[0].body['commandType']).toBe('ANSWER_OFFER');
    expect(server.commands[0].body['payload']).toEqual({ offerId: 'offer-1', decision: 'accept' });
  });
});
