/** Playthrough view: fallback disclosure, receipts, localized errors, scoped draft and reload preservation. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { ADVENTURE, CAMPAIGN, CampaignServer, MemoryStore, OP1, operation, resumeCard, state, syncProvider } from './fixtures';

/** Wait until the routed screen satisfies a predicate. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

describe('Campaign playthrough view', /** Register integrated screen checks against a simulated campaign server. */ () => {
  let server: CampaignServer;
  let drafts: MemoryStore;
  let sync: MemoryStore;
  beforeEach(/** Fresh server, router and storages per test. */ () => {
    server = new CampaignServer();
    drafts = new MemoryStore();
    sync = new MemoryStore();
    server.operations.set(OP1, operation(OP1, 'COMMITTED_NARRATION_PENDING', 5, true));
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: drafts }, syncProvider(sync)] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  it('shows the fallback disclosure, saved operations with receipts and keeps the draft', /** Text-only disclosure with a lang-tagged passage. */ async () => {
    // A record saved before a reload: the action was accepted and the cursor sits after its message.
    sync.setItem(`engine.campaign-sync.v1:["${ADVENTURE}","${CAMPAIGN}"]`, JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP1, commandType: 'USE_ABILITY', status: 'ACCEPTED', operationVersion: 0 }] }));
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-operation-id]') !== null && root.textContent!.includes('Saved; narration is pending'));
    const passage = root.querySelector('.starting-situation .passage')!;
    expect(passage.getAttribute('lang')).toBe('it');
    expect(passage.textContent).toBe('Una lanterna brilla.');
    expect(root.querySelector('[data-fallback]')!.textContent).toContain('original Italian');
    const text = root.textContent!;
    expect(text).toContain('Use an ability');
    expect(text).toContain('vigor: 12,000 → 11,000');
    expect(text).toContain('Draw 0: 17 on a 20-sided die');
    expect(text).toContain('Sending actions is not connected yet');
    // The send control stays disabled and nothing was posted.
    expect((root.querySelector('[data-send]') as HTMLButtonElement).disabled).toBe(true);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
    // The reloaded page re-queried the saved operation by identity and resumed after the saved cursor.
    expect(server.calls.some((url) => url.includes(`/operations/${OP1}?`))).toBe(true);
    expect(server.calls.some((url) => url.includes('/stream?') && decodeURIComponent(url).includes('after=0:1'))).toBe(true);

    const field = root.querySelector('#campaign-input') as HTMLTextAreaElement;
    field.value = 'Raise the lantern';
    field.setSelectionRange(6, 9);
    field.dispatchEvent(new Event('input'));
    expect(drafts.values.get(`engine.story-draft.v1:["${ADVENTURE}","${CAMPAIGN}"]`)).toContain('Raise the lantern');
    expect(sync.values.get(`engine.campaign-sync.v1:["${ADVENTURE}","${CAMPAIGN}"]`)).toContain(OP1);
  });

  it('hides the fallback notice for matching-language text and shows localized API errors', /** Disclosure only when a fallback happened. */ async () => {
    server.stateBody = state({ fallback: false, locale: 'en' });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('.starting-situation') !== null);
    expect(root.querySelector('[data-fallback]')).toBeNull();

    server.errorCode = 'COMMAND_STALE_REVISION';
    server.statusCode = 409;
    (root.querySelector('.campaign-playthrough button:not([data-send])') as HTMLButtonElement).click();
    await settle(harness, () => (root.querySelector('[data-error]')?.textContent ?? '').includes('The world has changed'));
    expect(root.textContent).not.toContain('COMMAND_STALE_REVISION');
  });

  it('shows the resume card with the pause notice and saved actions awaiting narration', /** P09.5: read-only, localized recap. */ async () => {
    server.resumeBody = resumeCard({ paused: true, awaiting: true });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-resume]') !== null);
    const card = root.querySelector('[data-resume]')!;
    expect(card.querySelector('[data-location]')!.textContent).toContain('Lantern Shelter');
    expect(card.querySelector('[data-location]')!.getAttribute('lang')).toBe('en');
    expect(card.querySelector('[data-present]')!.textContent).toContain('Warden');
    expect(card.querySelector('[data-resources]')!.textContent).toContain('vigor 11,000 of 12,000');
    expect(card.querySelector('[data-decision]')!.textContent).toContain('Say or do what you want next.');
    const paused = card.querySelector('[data-paused]')!;
    expect(paused.getAttribute('role')).toBe('alert');
    expect(paused.textContent).toContain('sample:lanterns 1.0.0 is not installed in this adventure.');
    const owed = card.querySelector(`[data-awaiting-id="${OP1}"]`)!;
    expect(owed.textContent).toContain('Narration is not available yet.');
    expect(owed.textContent).toContain('Saved as world revision 2.');
    expect(card.textContent).not.toContain('DEPENDENCY_MISSING');
    // Reading the card only issued GETs: nothing was submitted.
    expect(server.calls.some((url) => url.includes('/resume?'))).toBe(true);
    expect(server.calls.some((url) => url.includes('/commands'))).toBe(false);
  });

  it('shows a safe generic explanation for an unknown server code', /** Unknown text is never displayed. */ async () => {
    server.errorCode = 'SOMETHING_NEW';
    server.statusCode = 404;
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => root.querySelector('[data-error]') !== null);
    expect(root.querySelector('[data-error]')!.textContent).toContain('Something went wrong');
    expect(root.textContent).not.toContain('SOMETHING_NEW');
  });
});
