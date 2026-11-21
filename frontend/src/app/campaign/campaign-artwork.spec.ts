/** Stable item artwork, rarity frames and credits (P17.1-B): strict parsing, hash URLs, fallback and visible credits. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { ArtworkIndex, frameFor } from './campaign-artwork';
import { CampaignProtocolError, parseArtwork } from './campaign-protocol';
import { ADVENTURE, CAMPAIGN, CampaignServer, frame, MemoryStore, OP2, panelsBody, syncProvider } from './fixtures';

const SCOPE = { adventureId: ADVENTURE, campaignId: CAMPAIGN };
const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);

/** One listing entry as the server sends it; fields can be replaced to break exactly one. */
function entry(change: Record<string, unknown> = {}): Record<string, unknown> {
  return { category: 'item', subject: 'sword', appearanceRevision: 1, sha256: HASH_A, mediaType: 'image/png', width: 64, height: 64,
    credit: 'Original artwork, MIT', ...change };
}

/** A complete listing body for the fixture campaign. */
function listing(...entries: Record<string, unknown>[]): unknown {
  return { scope: SCOPE, artwork: entries };
}

describe('Artwork listing', /** Everything the server sends is checked before a screen uses it. */ () => {
  it('reads a complete listing', /** Values are carried exactly as sent. */ () => {
    const parsed = parseArtwork(listing(entry(), entry({ category: 'portrait', subject: 'mira', sha256: HASH_B })), ADVENTURE, CAMPAIGN);
    expect(parsed.map((item) => `${item.category}:${item.subject}`)).toEqual(['item:sword', 'portrait:mira']);
    expect(parsed[0]).toMatchObject({ sha256: HASH_A, mediaType: 'image/png', credit: 'Original artwork, MIT' });
  });

  it.each<[string, unknown]>([
    ['a foreign campaign', { scope: { adventureId: ADVENTURE, campaignId: '00000000-0000-4000-8000-0000000000ff' }, artwork: [] }],
    ['an unknown category', listing(entry({ category: 'banner' }))],
    ['an upper-case hash', listing(entry({ sha256: 'A'.repeat(64) }))],
    ['a short hash', listing(entry({ sha256: 'abc' }))],
    ['an unsupported media type', listing(entry({ mediaType: 'image/svg+xml' }))],
    ['a missing credit', listing(entry({ credit: ' ' }))],
    ['a negative revision', listing(entry({ appearanceRevision: -1 }))],
    ['an oversized listing', listing(...Array.from({ length: 501 }, () => entry()))],
    ['a non-object body', 'artwork'],
  ])('refuses %s', /** One broken field rejects the whole listing. */ (_name, body) => {
    expect(() => parseArtwork(body, ADVENTURE, CAMPAIGN)).toThrow(CampaignProtocolError);
  });
});

describe('Artwork index and frames', /** Lookup is by stable subject; frames follow the rarity tier only. */ () => {
  const entries = parseArtwork(listing(entry(), entry({ appearanceRevision: 2, sha256: HASH_B, credit: 'Studio sample, CC0' }),
    entry({ category: 'portrait', subject: 'sword', sha256: HASH_A }), entry({ category: 'music', subject: 'theme', mediaType: 'audio/ogg',
      width: 0, height: 0, sha256: 'c'.repeat(64), credit: 'Loop credit' })), ADVENTURE, CAMPAIGN);
  const index = new ArtworkIndex(entries, SCOPE);

  it('serves the newest appearance by hash inside the campaign scope', /** The URL names exactly the content shown. */ () => {
    expect(index.imageUrl('item', 'sword')).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_B}?adventureId=${ADVENTURE}`);
    expect(index.imageUrl('portrait', 'sword')).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_A}?adventureId=${ADVENTURE}`);
  });

  it('has no image for an unknown subject, another category or audio', /** The caller shows its neutral icon. */ () => {
    expect(index.imageUrl('item', 'coin')).toBeNull();
    expect(index.imageUrl('scene', 'sword')).toBeNull();
    expect(index.imageUrl('music', 'theme')).toBeNull();
  });

  it('lists the credit of each image in force once', /** The older appearance's credit is not shown. */ () => {
    expect(index.credits()).toEqual(['Studio sample, CC0', 'Original artwork, MIT']);
  });

  it('groups scene, map, portrait and interface images by category and leaves items and audio out', /** The gallery never draws audio. */ () => {
    const gallery = new ArtworkIndex(parseArtwork(listing(
      entry({ category: 'ui', subject: 'frame', sha256: 'd'.repeat(64) }), entry({ category: 'scene', subject: 'harbor', sha256: 'e'.repeat(64) }),
      entry({ category: 'scene', subject: 'camp', sha256: 'f'.repeat(64) }), entry({ category: 'portrait', subject: 'mira' }),
      entry(), entry({ category: 'music', subject: 'theme', mediaType: 'audio/ogg', width: 0, height: 0, sha256: 'c'.repeat(64) })),
      ADVENTURE, CAMPAIGN), SCOPE).gallery();
    expect(gallery.map((group) => `${group.category}:${group.items.map((item) => item.subject).join(',')}`))
      .toEqual(['scene:camp,harbor', 'portrait:mira', 'ui:frame']);
    expect(gallery[0].items[0].url).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${'f'.repeat(64)}?adventureId=${ADVENTURE}`);
    expect(new ArtworkIndex([], SCOPE).gallery()).toEqual([]);
  });

  it('chooses a frame from the declared tier only', /** Default tiers rank; a pack tier is unranked; no tier has no frame. */ () => {
    expect(frameFor(null)).toBe('none');
    expect(frameFor('common')).toBe('rank-1');
    expect(frameFor('Legendary')).toBe('rank-5');
    expect(frameFor('ordinary')).toBe('unranked');
    expect(frameFor('__proto__')).toBe('unranked');
  });
});

describe('Item artwork in the playthrough view', /** The panels show the stable icon, a neutral fallback and the credits. */ () => {
  let server: CampaignServer;
  let harness: RouterTestingHarness;
  beforeEach(/** Fresh server and router per test. */ () => {
    server = new CampaignServer();
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  /** Open the playthrough and wait until the panels have rendered and `ready` holds; a few extra turns let a failed read settle. */
  async function open(ready: (root: HTMLElement) => boolean): Promise<HTMLElement> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    for (let attempt = 0; attempt < 90 && !(root.querySelector('[data-credits]') !== null && ready(root)); attempt++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    for (let extra = 0; extra < 5; extra++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    expect(ready(root)).toBe(true);
    return root;
  }

  it('shows registered art, a neutral icon and the credits, with the tier written beside its frame', /** Text carries what the frame shows. */ async () => {
    server.artworkBody = listing(entry({ subject: 'sword' }));
    const root = await open((page) => page.querySelector('[data-item="sword"] img') !== null);
    const sword = root.querySelector('[data-item="sword"]')!;
    const image = sword.querySelector('img')!;
    expect(image.getAttribute('src')).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_A}?adventureId=${ADVENTURE}`);
    expect(image.getAttribute('alt')).toBe('');
    expect(sword.querySelector('.art-frame')!.getAttribute('data-frame')).toBe('unranked');
    expect(sword.querySelector('[data-rarity]')!.textContent).toContain('Ordinary');
    const coin = root.querySelector('[data-item="coin"]')!;
    expect(coin.querySelector('img')).toBeNull();
    expect(coin.querySelector('.art-frame')!.getAttribute('data-art')).toBe('fallback');
    expect(coin.querySelector('.art-frame')!.getAttribute('data-frame')).toBe('none');
    const worn = root.querySelector('[data-slot="main-hand"]')!;
    expect(worn.querySelector('img')!.getAttribute('src')).toContain(HASH_A);
    expect(worn.querySelector('[data-rarity]')!.textContent).toContain('Ordinary');
    expect(root.querySelector('[data-credits]')!.textContent).toContain('Original artwork, MIT');
    expect(server.calls.every((url) => !url.includes('/commands'))).toBe(true);
  });

  it('lists scene, map, portrait and interface images with thumbnails and credits, and no gallery without them', /** Categories stay apart. */ async () => {
    server.artworkBody = listing(entry({ subject: 'sword' }),
      entry({ category: 'scene', subject: 'harbor', sha256: HASH_B, credit: 'Harbor scene, MIT' }),
      entry({ category: 'portrait', subject: 'mira', sha256: 'c'.repeat(64), credit: 'Mira portrait, CC0' }));
    const root = await open((page) => page.querySelector('[data-gallery] img') !== null);
    const gallery = root.querySelector('[data-gallery]')!;
    expect([...gallery.querySelectorAll('[data-gallery-category]')].map((heading) => heading.textContent)).toEqual(['Scenes', 'Portraits']);
    const harbor = gallery.querySelector('[data-subject="harbor"]')!;
    expect(harbor.querySelector('img')!.getAttribute('src')).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_B}?adventureId=${ADVENTURE}`);
    expect(harbor.querySelector('img')!.getAttribute('alt')).toBe('');
    expect(harbor.textContent).toContain('harbor');
    expect(gallery.querySelector('[data-subject="sword"]')).toBeNull();
    const credits = root.querySelector('[data-credits]')!.textContent!;
    expect(credits).toContain('Harbor scene, MIT');
    expect(credits).toContain('Mira portrait, CC0');
  });

  it('reads the pack music once and lists its own track in the music credits; an unreadable listing changes nothing else (P17.2-D)', async () => {
    const track = { id: 'ford-dusk', mood: 'exploration', title: 'Ford at Dusk', creator: 'Project contributors', license: 'MIT',
      licenseUrl: 'https://opensource.org/license/mit', source: 'Original sample music', notices: 'Copyright notice.', modifications: 'None.',
      sha256: HASH_B, mediaType: 'audio/wav' };
    server.musicBody = { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, policy: { fight: 'tension' }, tracks: [track] };
    const root = await open((page) => page.querySelector('[data-music-credit="pack:ford-dusk"]') !== null);
    expect(root.querySelector('[data-music-credit="pack:ford-dusk"]')!.textContent).toContain('Original sample music');
    expect(server.calls.filter((url) => url.includes('/music?'))).toEqual([`/api/v1/campaigns/${CAMPAIGN}/music?adventureId=${ADVENTURE}`]);
    // Nothing is fetched for the track before the player turns music on.
    expect(server.calls.some((url) => url.includes(`/assets/${HASH_B}`))).toBe(false);
    TestBed.resetTestingModule();
    server = new CampaignServer();
    server.musicBody = { code: 'ASSET_STORAGE_UNAVAILABLE' };
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
    const plain = await open((page) => page.querySelector('[data-item="sword"]') !== null && server.calls.some((url) => url.includes('/music?')));
    expect(plain.querySelector('[data-music-credit^="pack:"]')).toBeNull();
    expect(plain.querySelector('[data-music-credit="wholesome"]')).not.toBeNull();
  });

  it('shows no gallery when no scene, map, portrait or interface image is in force', /** An empty section would be noise. */ async () => {
    server.artworkBody = listing(entry({ subject: 'sword' }));
    const root = await open((page) => page.querySelector('[data-item="sword"] img') !== null);
    expect(root.querySelector('[data-gallery]')).toBeNull();
  });

  it('shows only built-in icons and an honest credits note when the listing cannot be read', /** Decoration never blocks play. */ async () => {
    server.artworkBody = { code: 'ASSET_STORAGE_UNAVAILABLE' };
    const root = await open(() => server.calls.some((url) => url.includes('/artwork?')));
    expect(root.querySelector('[data-item="sword"] img')).toBeNull();
    expect(root.querySelector('[data-item="sword"] .art-frame')!.getAttribute('data-art')).toBe('fallback');
    expect(root.querySelector('[data-credits]')!.textContent).toContain('No artwork is credited');
    expect(root.querySelector('[data-panels]')).not.toBeNull();
  });
});

describe('Artwork identity across equip and reload', /** The same definition keeps its image, frame and tier whether carried, worn or re-opened. */ () => {
  let server: CampaignServer;
  let harness: RouterTestingHarness;
  let release: () => void;
  beforeEach(/** The stream is held until a test releases it, so the re-read after the equip is deterministic. */ () => {
    server = new CampaignServer();
    const gate = new Promise<void>((accept) => { release = accept; });
    const gated = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> =>
      String(input).includes('/stream?') ? gate.then(() => server.fetch(input, init)) : server.fetch(input, init);
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: gated },
      { provide: STORY_DRAFT_STORAGE, useValue: new MemoryStore() }, syncProvider(new MemoryStore())] });
  });
  afterEach(/** Releasing the gate lets a held poll finish; destroying the fixture aborts it. */ () => { release(); TestBed.resetTestingModule(); });

  /** Let the router and the follower run until `ready` holds, with a bounded number of turns. */
  async function until(root: HTMLElement, ready: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 120 && !ready(); attempt++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    for (let extra = 0; extra < 5; extra++) {
      await new Promise((accept) => setTimeout(accept, 0));
      await harness.fixture.whenStable();
    }
    expect(ready()).toBe(true);
    expect(root.isConnected).toBe(true);
  }

  /** What one row shows of an item's identity: image address, frame token, art kind and the written tier. */
  function identity(row: Element): Record<string, string | null> {
    const frameNode = row.querySelector('.art-frame')!;
    return { src: row.querySelector('img')?.getAttribute('src') ?? null, frame: frameNode.getAttribute('data-frame'),
      art: frameNode.getAttribute('data-art'), tier: row.querySelector('[data-rarity]')?.textContent?.replace(/\s+/g, ' ').trim() ?? null };
  }

  /** The playthrough body with the sword carried but not worn: same definition and rarity, no worn slot. */
  function unequipped(): unknown {
    const body = JSON.parse(JSON.stringify(panelsBody())) as Record<string, any>;
    body['inventory'][0]['wornIn'] = null;
    body['equipment'] = [];
    return body;
  }

  it('keeps image, frame and tier when the item is equipped and when the page is opened again', /** Identity follows the definition, not the slot. */ async () => {
    server.artworkBody = listing(entry({ subject: 'sword' }));
    server.panelsBody = unequipped();
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await until(root, () => root.querySelector('[data-item="sword"] img') !== null);
    expect(root.querySelector('[data-slot]')).toBeNull();
    const carried = identity(root.querySelector('[data-item="sword"]')!);
    expect(carried['src']).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_A}?adventureId=${ADVENTURE}`);
    expect(carried).toMatchObject({ frame: 'unranked', art: 'image' });
    expect(carried['tier']).toContain('Ordinary');

    // The commit: the same definition is now worn; a stream reset makes the page re-read the panels.
    server.panelsBody = panelsBody();
    server.streams = [[frame('0:7', 'stream.reset', { reason: 'CURSOR_EXPIRED' })], []];
    release();
    await until(root, () => root.querySelector('[data-slot="main-hand"]') !== null);
    expect(server.calls.filter((url) => url.includes('/panels?')).length).toBeGreaterThanOrEqual(2);
    expect(identity(root.querySelector('[data-item="sword"]')!)).toEqual(carried);
    expect(identity(root.querySelector('[data-slot="main-hand"]')!)).toEqual(carried);

    // Opening the page again, as after a reload, reads the same listing and the same panels.
    await harness.navigateByUrl('/interface-kit');
    expect((harness.routeNativeElement as HTMLElement).querySelector('[data-item="sword"]')).toBeNull();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const again = harness.routeNativeElement as HTMLElement;
    await until(again, () => again.querySelector('[data-slot="main-hand"] img') !== null);
    expect(identity(again.querySelector('[data-item="sword"]')!)).toEqual(carried);
    expect(identity(again.querySelector('[data-slot="main-hand"]')!)).toEqual(carried);
    expect(again.querySelector('[data-credits]')!.textContent).toContain('Original artwork, MIT');
    expect(server.calls.every((url) => !url.includes('/commands'))).toBe(true);
  });

  it('gives two items of one definition the same icon', /** Equivalent items reuse one image address. */ async () => {
    server.artworkBody = listing(entry({ subject: 'sword' }));
    const body = JSON.parse(JSON.stringify(unequipped())) as Record<string, any>;
    body['inventory'].push({ ...body['inventory'][0], itemId: OP2, name: body['inventory'][0].name });
    server.panelsBody = body;
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await until(root, () => root.querySelectorAll('[data-item="sword"] img').length === 2);
    const rows = [...root.querySelectorAll('[data-item="sword"]')].map(identity);
    expect(rows[0]).toEqual(rows[1]);
    expect(rows[0]['src']).toContain(HASH_A);
  });
});
