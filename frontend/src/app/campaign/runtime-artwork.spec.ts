/**
 * Runtime artwork requests on the playthrough screen (P17.6-C): strict parsing of both replies, an explicit request per subject,
 * pending, ready and failed states, the new image replacing the fallback, and a disabled or failing capability that leaves play
 * and the fallbacks untouched. The server is simulated; no image backend is involved.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { PreviewState } from '../preview/preview-state';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { CampaignProtocolError } from './campaign-protocol';
import { ADVENTURE, CAMPAIGN, CampaignServer, json, MemoryStore, narratedOperation, OP1, SPEAKER, syncProvider } from './fixtures';
import { RUNTIME_ARTWORK_POLL_MS } from './runtime-artwork';
import { ImageState, parseImageAnswer, parseImageRequests } from './runtime-artwork-protocol';

const SCOPE = { adventureId: ADVENTURE, campaignId: CAMPAIGN };
const HASH_SCENE = 'c'.repeat(64);

/** One subject as the server lists it. */
function item(category: string, subject: string, label: string, state: ImageState = 'missing', errorCode: string | null = null) {
  return { category, subject, label, state, errorCode };
}

/** A listing body; the default is an enabled capability with an unused budget. */
function listing(subjects: unknown[], capability = 'enabled', budget = { charged: 0, limit: 12, queued: 0, running: false }) {
  return { scope: SCOPE, capability, budget, subjects };
}

describe('Runtime artwork replies', /** Everything the server sends is checked before the screen uses it. */ () => {
  it('reads a complete listing and answer', /** Values are carried exactly as sent. */ () => {
    const parsed = parseImageRequests(listing([item('scene', 'trailhead', 'Lantern Shelter'), item('portrait', SPEAKER, 'Trail keeper', 'failed', 'image-job-timeout')]),
      ADVENTURE, CAMPAIGN);
    expect(parsed.capability).toBe('enabled');
    expect(parsed.subjects.map((subject) => `${subject.category}:${subject.state}`)).toEqual(['scene:missing', 'portrait:failed']);
    expect(parseImageAnswer({ admission: 'budget-exhausted', subject: item('item', 'coin', 'Coin') }).admission).toBe('budget-exhausted');
  });

  it.each<[string, unknown]>([
    ['a foreign scope', { ...listing([]), scope: { adventureId: ADVENTURE, campaignId: '00000000-0000-4000-8000-0000000000ff' } }],
    ['an extra subject field such as appearance text', listing([{ ...item('scene', 'trailhead', 'Shelter'), appearance: 'a hidden cave' }])],
    ['an unknown state', listing([item('scene', 'trailhead', 'Shelter', 'queued' as ImageState)])],
    ['a failure without a code', listing([item('scene', 'trailhead', 'Shelter', 'failed')])],
    ['a code on a subject that did not fail', listing([item('scene', 'trailhead', 'Shelter', 'missing', 'image-job-timeout')])],
    ['a path as subject', listing([item('item', '../coin', 'Coin')])],
    ['a map, which runtime jobs do not make', listing([item('map', 'trailhead', 'Shelter')])],
    ['an unknown capability', listing([], 'maybe')],
    ['an extra top-level field', { ...listing([]), prompts: [] }],
  ])('refuses %s', /** One broken field rejects the whole reply. */ (_name, body) => {
    expect(() => parseImageRequests(body, ADVENTURE, CAMPAIGN)).toThrow(CampaignProtocolError);
  });

  it('refuses an answer with an unknown admission', /** The note table is closed. */ () => {
    expect(() => parseImageAnswer({ admission: 'generated', subject: item('item', 'coin', 'Coin') })).toThrow(CampaignProtocolError);
  });
});

describe('Runtime artwork on the playthrough screen', /** The player asks; the screen follows the job without blocking play. */ () => {
  let server: CampaignServer;
  let harness: RouterTestingHarness;
  beforeEach(/** Fresh server, router, storages and reading preferences per test; polls are fast. */ () => {
    sessionStorage.clear();
    server = new CampaignServer();
    // A narrated operation known by identity, so the live story (and its scene) is on screen, as in the story spec.
    server.operations.set(OP1, narratedOperation(OP1));
    const sync = new MemoryStore();
    sync.setItem(`engine.campaign-sync.v1:["${ADVENTURE}","${CAMPAIGN}"]`, JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP1, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6 }] }));
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(sync), { provide: RUNTIME_ARTWORK_POLL_MS, useValue: 5 }] });
  });
  afterEach(/** Destroying the fixture aborts the follower and every artwork read. */ () => TestBed.resetTestingModule());

  /** Wait until the routed screen satisfies a predicate. */
  async function until(ready: (root: HTMLElement) => boolean): Promise<HTMLElement> {
    const root = harness.routeNativeElement as HTMLElement;
    for (let attempt = 0; attempt < 200 && !ready(root); attempt++) {
      await new Promise((accept) => setTimeout(accept, 2));
      await harness.fixture.whenStable();
    }
    expect(ready(root)).toBe(true);
    return root;
  }

  async function open(ready: (root: HTMLElement) => boolean): Promise<HTMLElement> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    return until(ready);
  }

  const subject = (root: HTMLElement, key: string) => root.querySelector(`[data-image-subject="${key}"]`) as HTMLElement | null;
  const state = (root: HTMLElement, key: string) => subject(root, key)?.getAttribute('data-state') ?? null;

  it('asks for a scene on request, shows it pending, then ready in the story, and keeps Send working', /** Pending to ready. */ async () => {
    let scene: ImageState = 'missing';
    server.imageRequestsBody = () => listing([item('scene', 'trailhead', 'Lantern Shelter', scene), item('portrait', SPEAKER, 'Trail keeper'),
      item('item', 'coin', 'Coin')]);
    server.imageReply = () => { scene = 'pending'; return json({ admission: 'queued', subject: item('scene', 'trailhead', 'Lantern Shelter', 'pending') }, 202); };
    const root = await open((page) => subject(page, 'scene:trailhead') !== null);
    expect(root.querySelector('[data-image-budget]')!.textContent).toContain('0 of 12');
    expect(root.querySelector('[data-scene-fallback]')).not.toBeNull();
    // Reading the list asked for nothing.
    expect(server.imageAsks).toEqual([]);
    const button = subject(root, 'scene:trailhead')!.querySelector('[data-request-image]') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe('Ask for an image of Lantern Shelter');
    button.click();
    await until((page) => state(page, 'scene:trailhead') === 'pending');
    expect(server.imageAsks).toEqual([{ csrf: 'token', body: { category: 'scene', subject: 'trailhead' } }]);
    expect(subject(root, 'scene:trailhead')!.textContent).toContain('Being made…');
    expect(subject(root, 'scene:trailhead')!.querySelector('[data-image-note]')!.textContent).toContain('you can keep playing');
    expect(subject(root, 'scene:trailhead')!.querySelector('[data-request-image]')).toBeNull();
    // Play is not blocked: the composer and the other subjects stay usable while the job runs.
    expect(root.querySelector('#campaign-input')).not.toBeNull();
    expect((subject(root, 'item:coin')!.querySelector('[data-request-image]') as HTMLButtonElement).disabled).toBe(false);

    // The job finishes: the next poll reads it ready, the artwork listing is re-read and the scene replaces the fallback.
    server.artworkBody = { scope: SCOPE, artwork: [{ category: 'scene', subject: 'trailhead', appearanceRevision: 1, sha256: HASH_SCENE,
      mediaType: 'image/png', width: 1216, height: 832, credit: 'Generated image for this campaign' }] };
    scene = 'ready';
    await until((page) => page.querySelector('.scene img') !== null);
    expect(root.querySelector('.scene img')!.getAttribute('src')).toContain(HASH_SCENE);
    expect(state(root, 'scene:trailhead')).toBe('ready');
    expect(root.querySelector('[data-image-announcement]')!.textContent).toContain('Image ready: Lantern Shelter');
    expect(server.calls.filter((url) => url.includes('/commands'))).toEqual([]);
  });

  it('shows a failed job with its reason and a retry, and a refusal beside the subject', /** Errors stay local. */ async () => {
    let portrait: ImageState = 'missing';
    server.imageRequestsBody = () => listing([item('portrait', SPEAKER, 'Trail keeper', portrait, portrait === 'failed' ? 'image-job-timeout' : null),
      item('item', 'coin', 'Coin')]);
    server.imageReply = () => {
      const asked = server.imageAsks.at(-1)!.body;
      if (asked['category'] === 'item') return json({ admission: 'budget-exhausted', subject: item('item', 'coin', 'Coin') });
      portrait = 'pending';
      return json({ admission: 'queued', subject: item('portrait', SPEAKER, 'Trail keeper', 'pending') }, 202);
    };
    const root = await open((page) => subject(page, `portrait:${SPEAKER}`) !== null);
    (subject(root, `portrait:${SPEAKER}`)!.querySelector('[data-request-image]') as HTMLButtonElement).click();
    await until((page) => state(page, `portrait:${SPEAKER}`) === 'pending');
    portrait = 'failed';
    await until((page) => state(page, `portrait:${SPEAKER}`) === 'failed');
    const failed = subject(root, `portrait:${SPEAKER}`)!;
    expect(failed.textContent).toContain('Could not be made: it took too long.');
    expect(failed.querySelector('[data-request-image]')!.textContent!.trim()).toBe('Try again');
    expect(root.querySelector('[data-image-announcement]')!.textContent).toContain('No image could be made: Trail keeper');

    (subject(root, 'item:coin')!.querySelector('[data-request-image]') as HTMLButtonElement).click();
    await until((page) => (subject(page, 'item:coin')!.querySelector('[data-image-note]')?.textContent ?? '').includes('limit'));
    expect(state(root, 'item:coin')).toBe('missing');
    expect(subject(root, 'item:coin')!.querySelector('[data-image-note]')!.textContent).toContain('The image limit for this period is used up.');
  });

  it('offers no request when generation is off and keeps the fallbacks', /** Disabled capability. */ async () => {
    server.imageRequestsBody = () => listing([item('scene', 'trailhead', 'Lantern Shelter'), item('item', 'coin', 'Coin')], 'disabled');
    const root = await open((page) => subject(page, 'scene:trailhead') !== null);
    expect(root.querySelector('[data-image-capability]')!.textContent).toContain('Making new images is turned off.');
    expect(root.querySelector('[data-request-image]')).toBeNull();
    expect(root.querySelector('[data-scene-fallback]')).not.toBeNull();
    expect(root.querySelector('[data-item="coin"] .art-frame')!.getAttribute('data-art')).toBe('fallback');
  });

  it('says so when the list cannot be read and does not retry a missing route', /** A failed read is decoration only. */ async () => {
    const root = await open((page) => page.querySelector('[data-image-list-failed]') !== null);
    expect(root.querySelector('[data-image-list-failed]')!.textContent).toContain('cannot be listed right now');
    const reads = server.calls.filter((url) => url.includes('/artwork/requests?')).length;
    await new Promise((accept) => setTimeout(accept, 40));
    expect(server.calls.filter((url) => url.includes('/artwork/requests?')).length).toBe(reads);
    expect(root.querySelector('#campaign-input')).not.toBeNull();
  });

  it('shows no section and reads no list in the text-focused layout', /** That layout asks for no image anywhere. */ async () => {
    server.imageRequestsBody = () => listing([item('scene', 'trailhead', 'Lantern Shelter')]);
    TestBed.inject(PreviewState).setReadingLayout('text-focused');
    const root = await open((page) => page.querySelector('[data-live-story]') !== null && page.querySelector('[data-item="coin"]') !== null);
    expect(root.querySelector('.runtime-artwork')).toBeNull();
    expect(server.calls.some((url) => url.includes('/artwork/requests'))).toBe(false);
  });
});
