/**
 * Consumer side of the real-server reload contract (P05.6, QA-35).
 *
 * `contract/campaign-reload-transcript.json` is recorded by the backend test
 * `CampaignInterfaceContractTest` against the actual servlet stack and PostgreSQL, and is kept
 * byte-identical to the backend golden file by the repository `contracts` check. These specs
 * replay those exact bytes: first through every validator, then through the routed playthrough
 * view, `CampaignSession` and `UiLanguage` across an explicit EN→IT→EN reload. The replaying
 * server answers only requests the real server was asked in the current or an earlier phase and
 * never serves a fixture-side POST to the interface, so an extra, renamed or submitting request
 * fails the spec. Wording assertions here use the English source catalog; the same replies are
 * rendered with the reviewed Italian catalog by `../italian-variant.spec.ts`, and the build's
 * parity check guarantees that the compiled `it` bundle carries those targets. The replay server
 * itself lives in `contract/transcript-server.ts`.
 */
import { registerLocaleData } from '@angular/common';
import italian from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH, parseAdventure, parsePlaythroughs } from '../library/saved-library-client';
import { PreviewState } from '../preview/preview-state';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { LANGUAGE_BROWSER, LanguageBrowser, UiLanguage } from '../preview/ui-language';
import { campaignErrorMessage, receiptLines } from './campaign-messages';
import { FrameDecoder, parseCampaignView, parseOperation, PublicReceipt } from './campaign-protocol';
import { CampaignSession } from './campaign-session';
import {
  ADVENTURE, CAMPAIGN, DRAFT_KEY, Exchange, EXCHANGES, MOVE, ROUTE, SCOPE, SYNC_KEY, TRANSCRIPT as transcript, TranscriptServer, WAIT,
} from './contract/transcript-server';
import { MemoryStore, syncProvider } from './fixtures';

/** Find the single recorded exchange for a phase and path fragment. */
function recorded(phase: string, fragment: string): Exchange {
  const matches = EXCHANGES.filter((entry) => entry.phase === phase && entry.path.includes(fragment));
  expect(matches, `${phase} ${fragment}`).toHaveLength(1);
  return matches[0];
}

/** Find the stream exchange recorded for a phase with an exact query suffix (empty for the head request). */
function stream(phase: string, query: string): Exchange {
  const path = `/api/v1/campaigns/${CAMPAIGN}/stream?adventureId=${ADVENTURE}${query}`;
  const matches = EXCHANGES.filter((entry) => entry.phase === phase && entry.path === path);
  expect(matches, `${phase} ${path}`).toHaveLength(1);
  return matches[0];
}

/** Poll real time until a predicate holds; the replayed stream uses the view's real pauses. */
async function until(harness: RouterTestingHarness, ready: () => boolean, label: string): Promise<void> {
  for (let attempt = 0; attempt < 300 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 20));
    await harness.fixture.whenStable();
  }
  expect(ready(), label).toBe(true);
}

describe('Campaign interface contract against recorded real-server replies', /** Register cross-stack replay checks. */ () => {
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  it('accepts every recorded real reply with the browser validators unchanged', /** Field names, enumerations, framing and codes match the producer. */ () => {
    expect(transcript.schemaVersion).toBe(1);
    expect(EXCHANGES.filter((entry) => entry.client === 'fixture').every((entry) => entry.method === 'POST')).toBe(true);
    expect(EXCHANGES.filter((entry) => entry.client === 'page').every((entry) => entry.method === 'GET')).toBe(true);

    expect(parseAdventure(JSON.parse(recorded('first-load', `/adventures/${ADVENTURE}`).body)).adventureId).toBe(ADVENTURE);
    for (const phase of ['first-load', 'after-reload']) {
      const listed = parsePlaythroughs(JSON.parse(recorded(phase, '/campaigns?').body), ADVENTURE);
      expect(listed.map((entry) => entry.campaignId)).toEqual([CAMPAIGN]);
    }

    // The campaign narrates in Italian while the page starts in English: the text carries its own language.
    const first = parseCampaignView(JSON.parse(recorded('first-load', `/campaigns/${CAMPAIGN}/state?`).body), ADVENTURE, CAMPAIGN);
    expect(first.stateRevision).toBe(0);
    expect(first.startingSituation).toMatchObject({ locale: 'it', fallback: false });
    expect(first.startingSituation!.text).toContain('sentiero');
    for (const phase of ['before-reload', 'after-reload']) {
      const later = parseCampaignView(JSON.parse(recorded(phase, `/campaigns/${CAMPAIGN}/state?`).body), ADVENTURE, CAMPAIGN);
      expect(later.stateRevision).toBe(1);
      expect(later.startingSituation).toBeNull();
    }

    const submitted = parseOperation(JSON.parse(recorded('actions', '/commands?').body), CAMPAIGN);
    expect(submitted).toMatchObject({ operationId: MOVE, commandType: 'MOVE', status: 'COMMITTED', committedRevision: 1 });
    const cancelled = parseOperation(JSON.parse(recorded('during-reload', '/cancel?').body), CAMPAIGN);
    expect(cancelled).toMatchObject({ operationId: WAIT, status: 'CANCELLED', receipt: null });
    const settled = parseOperation(JSON.parse(recorded('after-reload', `/operations/${MOVE}?`).body), CAMPAIGN);
    expect(settled.status).toBe('COMMITTED_NARRATION_PENDING');
    expect(settled.receipt).toEqual(submitted.receipt);
    expect(parseOperation(JSON.parse(recorded('after-reload', `/operations/${WAIT}?`).body), CAMPAIGN).status).toBe('CANCELLED');

    const frames = (phase: string, query: string) => new FrameDecoder(CAMPAIGN).push(stream(phase, query).body)
      .map((event) => `${event.type}|${event.id}|${event.status ?? ''}`);
    expect(frames('first-load', '')).toEqual(['stream.ready|0:0|']);
    expect(frames('first-load', '&after=0%3A0')).toEqual([]);
    expect(frames('before-reload', '&after=0%3A0')).toEqual(['operation.accepted|0:1|ACCEPTED', 'operation.progress|0:2|VALIDATING',
      'state.committed|0:3|COMMITTED', 'operation.progress|0:4|NARRATING', 'narrative.pending|0:5|COMMITTED_NARRATION_PENDING',
      'operation.accepted|0:6|ACCEPTED']);
    expect(frames('after-reload', '&after=0%3A6')).toEqual(['operation.cancelled|0:7|CANCELLED']);
    expect(frames('after-reload', '&after=0%3A7')).toEqual([]);
    const reset = new FrameDecoder(CAMPAIGN).push(stream('after-reload', '&after=0%3A99').body);
    expect(reset.map((event) => [event.type, event.id, event.reason])).toEqual([['stream.reset', '0:7', 'CURSOR_UNKNOWN']]);
    const pending = new FrameDecoder(CAMPAIGN).push(stream('before-reload', '&after=0%3A0').body).find((event) => event.type === 'narrative.pending');
    expect(pending?.reason).toBe('NARRATION_UNAVAILABLE');

    // The real error code has a specific localized sentence, never the generic fallback or the raw code.
    const missing = recorded('after-reload', `/campaigns/${transcript.identities.foreignCampaignId}/state?`);
    expect(missing.status).toBe(404);
    const message = campaignErrorMessage(JSON.parse(missing.body).code);
    expect(message).toBe('This playthrough is unavailable.');
    expect(message).not.toBe(campaignErrorMessage(null));

    // Both variants format the same committed mechanics; only digit grouping may differ.
    registerLocaleData(italian);
    const receipt = submitted.receipt as PublicReceipt;
    expect(receiptLines(receipt, 'en')).toEqual(['Saved as world revision 1.', 'Moved to grove.', 'World time passed: 0 s.',
      'The game turn advanced.']);
    expect(receiptLines(receipt, 'it')).toHaveLength(4);
  });

  it('keeps the draft, operation identities and stream cursor through an EN→IT→EN reload', /** The page never submits and never re-applies a delivery. */ async () => {
    registerLocaleData(italian);
    const server = new TranscriptServer();
    const drafts = new MemoryStore();
    const sync = new MemoryStore();
    const device = new MemoryStore();
    const tab = new MemoryStore();
    const navigate = vi.fn();

    /** Load the routed playthrough in one compiled variant, sharing device/tab storage like a real reload. */
    async function open(locale: 'en' | 'it') {
      TestBed.resetTestingModule();
      const browser: LanguageBrowser = { base: `http://127.0.0.1:8080/${locale}/`,
        href: `http://127.0.0.1:8080/${locale}${ROUTE}`, device, tab, navigate };
      TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
        { provide: STORY_DRAFT_STORAGE, useValue: drafts }, syncProvider(sync), { provide: LOCALE_ID, useValue: locale },
        { provide: LANGUAGE_BROWSER, useValue: browser }, { provide: PreviewState, useValue: new PreviewState(tab) }] });
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl(ROUTE);
      return { harness, root: harness.routeNativeElement as HTMLElement };
    }
    const status = (root: HTMLElement, id: string) => root.querySelector(`[data-operation-id="${id}"]`)?.getAttribute('data-status');
    const cursor = () => (JSON.parse(sync.values.get(SYNC_KEY) ?? '{}') as { cursor?: string }).cursor;

    // English page before any action: Italian narrative text tagged with its language, cursor at the head.
    let page = await open('en');
    await until(page.harness, () => cursor() === '0:0' && page.root.querySelector('.starting-situation .passage') !== null, 'first load');
    const passage = page.root.querySelector('.starting-situation .passage')!;
    expect(passage.getAttribute('lang')).toBe('it');
    expect(page.root.querySelector('[data-fallback]')).toBeNull();
    const field = page.root.querySelector('#campaign-input') as HTMLTextAreaElement;
    field.value = 'Raise the lantern and listen';
    field.setSelectionRange(10, 17);
    field.dispatchEvent(new Event('input'));
    expect(drafts.values.get(DRAFT_KEY)).toContain('Raise the lantern and listen');

    // Another client moved the character and left a second action in flight; the page follows the stream.
    server.advance('before-reload');
    await until(page.harness, () => status(page.root, WAIT) === 'ACCEPTED' && cursor() === '0:6' &&
      page.root.querySelector('.starting-situation') === null, 'stream delivery and state re-read');
    expect(status(page.root, MOVE)).toBe('COMMITTED_NARRATION_PENDING');
    expect(page.root.textContent).toContain('Moved to grove.');
    expect(page.root.textContent).toContain('Narration is not available yet');
    expect(page.root.textContent).toContain('No decision is waiting for you');

    // Explicit language change: the record verifies with the action still pending, then the IT variant loads.
    let language = TestBed.inject(UiLanguage);
    expect(language.change('it')).toBe(true);
    expect(navigate).toHaveBeenLastCalledWith(`/it${ROUTE}`);
    const saved = JSON.parse(sync.values.get(SYNC_KEY)!) as { cursor: string; operations: { operationId: string; status: string }[] };
    expect(saved.cursor).toBe('0:6');
    expect(saved.operations.map((entry) => [entry.operationId, entry.status])).toEqual([[MOVE, 'COMMITTED_NARRATION_PENDING'], [WAIT, 'ACCEPTED']]);
    expect(JSON.stringify(saved)).not.toContain('Raise the lantern');

    // The in-flight action was cancelled while the page reloaded.
    server.advance('after-reload');
    const before = server.calls.length;
    page = await open('it');
    await until(page.harness, () => status(page.root, WAIT) === 'CANCELLED' && cursor() === '0:7', 'reconciled Italian page');
    const reloaded = server.calls.slice(before);
    // Pending work and the dropped receipt were re-queried by identity; the stream resumed after the saved cursor.
    expect(reloaded).toContain(`GET /api/v1/campaigns/${CAMPAIGN}/operations/${WAIT}?adventureId=${ADVENTURE}`);
    expect(reloaded).toContain(`GET /api/v1/campaigns/${CAMPAIGN}/operations/${MOVE}?adventureId=${ADVENTURE}`);
    expect(reloaded.filter((call) => call.includes('/stream?'))[0]).toContain('after=0%3A6');
    expect(reloaded.some((call) => call.includes('/stream?') && call.includes('after=0%3A0'))).toBe(false);
    expect(page.root.textContent).toContain('Moved to grove.');
    expect((page.root.querySelector('#campaign-input') as HTMLTextAreaElement).value).toBe('Raise the lantern and listen');
    expect(TestBed.inject(CampaignSession).operations(SCOPE)).toHaveLength(2);

    // Back to English: nothing is pending, so only the stream (and the dropped receipt) is read again.
    language = TestBed.inject(UiLanguage);
    expect(language.change('en')).toBe(true);
    expect(navigate).toHaveBeenLastCalledWith(`/en${ROUTE}`);
    const again = server.calls.length;
    page = await open('en');
    await until(page.harness, () => page.root.textContent!.includes('Moved to grove.') && status(page.root, WAIT) === 'CANCELLED' &&
      server.calls.slice(again).some((call) => call.includes('after=0%3A7')), 'English page after the round trip');
    expect(server.calls.slice(again).some((call) => call.includes(`/operations/${WAIT}?`))).toBe(false);
    expect(cursor()).toBe('0:7');
    expect((page.root.querySelector('#campaign-input') as HTMLTextAreaElement).value).toBe('Raise the lantern and listen');
    expect(JSON.parse(drafts.values.get(DRAFT_KEY)!)).toMatchObject({ text: 'Raise the lantern and listen', start: 10, end: 17 });

    // Every request was one the real server answered, and the interface never wrote anything.
    expect(server.unrecorded).toEqual([]);
    expect(server.calls.every((call) => call.startsWith('GET '))).toBe(true);
  });

  it('adopts the head after an unknown saved cursor and re-queries instead of replaying', /** A reset never applies history twice. */ async () => {
    const server = new TranscriptServer();
    server.advance('after-reload');
    const sync = new MemoryStore();
    sync.setItem(SYNC_KEY, JSON.stringify({ version: 1, cursor: '0:99', operations: [
      { operationId: WAIT, commandType: 'WAIT', status: 'ACCEPTED', operationVersion: 0 }] }));
    TestBed.configureTestingModule({ providers: [syncProvider(sync), { provide: LIBRARY_FETCH, useValue: server.fetch }] });
    const session = TestBed.inject(CampaignSession);
    const result = await session.reconcile(SCOPE, new AbortController().signal);
    expect(result).toMatchObject({ reset: 'CURSOR_UNKNOWN', applied: 0, stateRevision: 1 });
    expect(session.cursor(SCOPE)).toBe('0:7');
    expect(session.operations(SCOPE).find((entry) => entry.operationId === WAIT)?.status).toBe('CANCELLED');
    expect(server.unrecorded).toEqual([]);
  });
});
