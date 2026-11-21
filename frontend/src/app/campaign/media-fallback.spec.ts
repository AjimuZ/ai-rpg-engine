/**
 * Missing art and audio never block play, and media carries no secret (P17.5-A): the playthrough screen is driven with images that fail,
 * artwork and music listings that cannot be read, and a listing whose rows carry fields the contract does not define. In every case the
 * story stays readable and a typed reply is still sent; no media reference, attribute or request holds anything but the public hash route.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { parseArtwork } from './campaign-protocol';
import { ADVENTURE, CAMPAIGN, CampaignServer, MemoryStore, narratedOperation, OP1, SPEAKER, syncProvider } from './fixtures';

const HASH_SCENE = 'a'.repeat(64);
const HASH_PORTRAIT = 'b'.repeat(64);
const HASH_ITEM = 'c'.repeat(64);
/** Text a hidden fact would leave behind; it is planted where the server could never legitimately put it. */
const HIDDEN_MARKER = 'HIDDEN-TWIST-MARKER';
const HASH_ROUTE = new RegExp(`^/api/v1/campaigns/${CAMPAIGN}/assets/[0-9a-f]{64}\\?adventureId=${ADVENTURE}$`);

/** One listing row; `extra` adds fields the contract does not define, as a careless or hostile server might. */
function row(category: string, subject: string, sha256: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { category, subject, appearanceRevision: 1, sha256, mediaType: 'image/png', width: 64, height: 64, credit: 'Sample art', ...extra };
}

/** Artwork for the scene, the speaker's portrait and the sword; every row carries secret-bearing extras. */
function listingWithExtras(): unknown {
  const extra = { prompt: `${HIDDEN_MARKER} prompt`, path: `/srv/${HIDDEN_MARKER}.png`, note: HIDDEN_MARKER,
    url: `https://example.invalid/${HIDDEN_MARKER}`, alt: HIDDEN_MARKER };
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, artwork: [row('scene', 'trailhead', HASH_SCENE, extra),
    row('portrait', SPEAKER, HASH_PORTRAIT, extra), row('item', 'sword', HASH_ITEM, extra)] };
}

describe('Media listing fields', /** Only the defined fields cross from the server into the page. */ () => {
  it('keeps exactly the contract fields of each artwork row and drops anything else', /** Extras can never reach a screen. */ () => {
    const parsed = parseArtwork(listingWithExtras(), ADVENTURE, CAMPAIGN);
    expect(parsed).toHaveLength(3);
    for (const entry of parsed) {
      expect(Object.keys(entry).sort()).toEqual(['appearanceRevision', 'category', 'credit', 'height', 'mediaType', 'sha256', 'subject', 'width']);
    }
    expect(JSON.stringify(parsed)).not.toContain(HIDDEN_MARKER);
  });
});

describe('Playthrough without working media', /** Art and audio are decoration; play never waits for them. */ () => {
  let server: CampaignServer;
  beforeEach(/** Fresh server with a narrated operation the screen reads, as after a reload. */ () => {
    sessionStorage.clear();
    server = new CampaignServer();
    server.operations.set(OP1, narratedOperation(OP1));
    const sync = new MemoryStore();
    sync.setItem(`engine.campaign-sync.v1:["${ADVENTURE}","${CAMPAIGN}"]`, JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP1, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6 }] }));
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(sync)] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  /** Wait, with a bounded number of turns, until the routed screen satisfies a predicate. */
  async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 90 && !ready(); attempt++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    expect(ready()).toBe(true);
  }
  async function open(ready: (root: HTMLElement) => boolean): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => ready(root));
    return { harness, root };
  }
  /** Choose the first quick reply, press Send and wait until the server holds one typed command. */
  async function replyAndSend(harness: RouterTestingHarness, root: HTMLElement): Promise<void> {
    await settle(harness, () => root.querySelector('[data-suggestion]') !== null);
    (root.querySelector('[data-suggestion]') as HTMLButtonElement).click();
    const send = root.querySelector('[data-send]') as HTMLButtonElement;
    await settle(harness, () => !send.disabled);
    send.click();
    await settle(harness, () => server.commands.length === 1);
    expect(server.commands[0].body).toMatchObject({ commandType: 'SPEAK', payload: { recipientActorId: SPEAKER, text: 'I will come with you.' } });
  }

  it('refers to media only by the public hash route and shows none of a row\'s extra fields', /** No path, URL, prompt or note reaches the DOM or a request. */ async () => {
    server.artworkBody = listingWithExtras();
    const { root } = await open((page) => page.querySelector('.scene img') !== null && page.querySelector('[data-item="sword"] img') !== null);
    const images = [...root.querySelectorAll('img')];
    expect(images.length).toBeGreaterThanOrEqual(3);
    for (const image of images) {
      expect(image.getAttribute('src')).toMatch(HASH_ROUTE);
      expect(image.hasAttribute('title')).toBe(false);
    }
    // The scene is described by the public place name only; portrait and icons are decorative.
    expect(root.querySelector('.scene img')!.getAttribute('alt')).toBe('Lantern Shelter');
    expect(root.querySelector('.portrait')!.getAttribute('alt')).toBe('');
    expect(root.querySelector('[data-item="sword"] img')!.getAttribute('alt')).toBe('');
    expect(root.innerHTML).not.toContain(HIDDEN_MARKER);
    expect(root.innerHTML).not.toContain('example.invalid');
    expect(root.innerHTML).not.toContain('/srv/');
    expect(server.calls.some((url) => url.includes(HIDDEN_MARKER) || url.includes('example.invalid'))).toBe(false);
    expect(server.calls.filter((url) => url.includes('/assets/'))).toEqual([]);
  });

  it('keeps the story, the panels and Send working when every registered image fails to load', /** A 404 or a broken file is decoration lost, not play lost. */ async () => {
    server.artworkBody = listingWithExtras();
    const { harness, root } = await open((page) => page.querySelector('.scene img') !== null && page.querySelector('[data-item="sword"] img') !== null);
    for (const image of [...root.querySelectorAll('img')]) image.dispatchEvent(new Event('error'));
    const story = root.querySelector('[data-live-story]')!;
    await settle(harness, () => story.getAttribute('data-scene') === 'failed' && root.querySelector('img') === null);
    expect(root.querySelector('[data-scene-fallback]')!.textContent).toContain('Lantern Shelter');
    expect(root.querySelector('.portrait-fallback')!.textContent!.trim()).toBe('T');
    expect(root.querySelector('.passage')!.textContent).toBe('Will you come with me?');
    expect(root.querySelector('[data-item="sword"] .art-frame')!.getAttribute('data-art')).toBe('fallback');
    expect(root.querySelector('[data-panels]')).not.toBeNull();
    await replyAndSend(harness, root);
    expect(root.innerHTML).not.toContain(HIDDEN_MARKER);
  });

  it('keeps the story and Send working when neither the artwork nor the music listing can be read', /** Storage trouble loses only decoration. */ async () => {
    server.artworkBody = { code: 'ASSET_STORAGE_UNAVAILABLE' };
    server.musicBody = { code: 'ASSET_STORAGE_UNAVAILABLE' };
    const { harness, root } = await open((page) => page.querySelector('[data-suggestion]') !== null
      && server.calls.some((url) => url.includes('/artwork?')) && server.calls.some((url) => url.includes('/music?')));
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('audio')).toBeNull();
    expect(root.querySelector('[data-live-story]')!.getAttribute('data-scene')).toBe('none');
    expect(root.querySelector('[data-scene-fallback]')!.textContent).toContain('Lantern Shelter');
    expect(root.querySelector('.passage')!.textContent).toBe('Will you come with me?');
    expect(root.querySelector('[data-credits]')!.textContent).toContain('No artwork is credited');
    await replyAndSend(harness, root);
  });
});
