/**
 * The pack music listing (P17.2-D): the strict parser of `GET .../music`, and the player preferring a scope's own pack
 * tracks under the pack's policy. Uses a fake audio element, so this proves the rules, not real browser playback.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { CombatView } from '../campaign/campaign-protocol';
import { creditText, mediaUrl, recordingFor } from './music-catalog';
import { moodOf } from './music-mood';
import { NO_PACK_MUSIC, parseMusicListing } from './music-pack';
import { MUSIC_AUDIO, MUSIC_LOCAL, MUSIC_STORAGE, MUSIC_TICK, MusicAudio, MusicPlayer } from './music-player';

const ADVENTURE = '00000000-0000-4000-8000-00000000000a';
const CAMPAIGN = '00000000-0000-4000-8000-0000000000a1';
const OTHER = '00000000-0000-4000-8000-0000000000b2';
const HASH = 'd'.repeat(64);

function track(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'ford-dusk', mood: 'exploration', title: 'Ford at Dusk', creator: 'Project contributors', license: 'MIT',
    licenseUrl: 'https://opensource.org/license/mit', source: 'Made by tools/sample-music/make-sample-music.mjs',
    notices: 'Copyright (c) 2026 Project contributors.', modifications: 'None.', sha256: HASH, mediaType: 'audio/wav', ...overrides,
  };
}

function listing(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, policy: { fight: 'tension', offered: 'tension' }, tracks: [track()], ...overrides };
}

/** A minimal combat projection in a fight, which the default policy maps to `combat`. */
const FIGHT = { encounter: { outcome: null }, offers: [] } as unknown as CombatView;

describe('pack music listing', /** Register parser cases. */ () => {
  it('reads the policy over the defaults and addresses a track only by its hash on the engine route', () => {
    const music = parseMusicListing(listing(), ADVENTURE, CAMPAIGN);
    expect(music.policy).toEqual({ quiet: 'exploration', offered: 'tension', fight: 'tension', won: 'victory', lost: 'defeat', settled: 'exploration' });
    expect(moodOf(FIGHT, music.policy!)).toBe('tension');
    expect(moodOf(FIGHT)).toBe('combat');
    const [dusk] = music.tracks;
    expect(dusk.id).toBe('pack:ford-dusk');
    expect(mediaUrl(dusk)).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH}?adventureId=${ADVENTURE}`);
    expect(creditText(dusk)).toContain('Source: Made by tools/sample-music/make-sample-music.mjs');
    expect(creditText(dusk)).toContain(`file SHA-256 ${HASH}`);
    expect(creditText(dusk)).toContain('License: MIT License (MIT), https://opensource.org/license/mit');
    expect(parseMusicListing(listing({ policy: null, tracks: [] }), ADVENTURE, CAMPAIGN)).toEqual(NO_PACK_MUSIC);
  });

  it('refuses anything that could name hidden state, reach another origin or drop a credit', () => {
    const noNotices = track();
    delete noNotices['notices'];
    const refused: [string, unknown][] = [
      ['hidden state key', listing({ policy: { 'raider-plan': 'combat' } })],
      ['unknown mood', listing({ policy: { fight: 'battle' } })],
      ['empty policy object', listing({ policy: {} })],
      ['other scope', listing({ scope: { adventureId: ADVENTURE, campaignId: OTHER } })],
      ['extra body key', { ...listing(), cues: [] }],
      ['url field', listing({ tracks: [track({ url: 'https://evil.example/a.ogg' })] })],
      ['missing notices', listing({ tracks: [noNotices] })],
      ['blank creator', listing({ tracks: [track({ creator: ' ' })] })],
      ['control character', listing({ tracks: [track({ title: 'a\nb' })] })],
      ['unadmitted license', listing({ tracks: [track({ license: 'CC-BY-NC-4.0', licenseUrl: 'https://creativecommons.org/licenses/by-nc/4.0/' })] })],
      ['wrong license url', listing({ tracks: [track({ licenseUrl: 'https://evil.example/mit' })] })],
      ['bad hash', listing({ tracks: [track({ sha256: '../../etc/passwd' })] })],
      ['image media type', listing({ tracks: [track({ mediaType: 'image/png' })] })],
      ['second track of one mood', listing({ tracks: [track(), track({ id: 'again' })] })],
    ];
    for (const [name, body] of refused) expect(() => parseMusicListing(body, ADVENTURE, CAMPAIGN), name).toThrow();
  });
});

class FakeAudio implements MusicAudio {
  loop = false;
  volume = 1;
  playing = false;
  onerror: ((event: unknown) => void) | null = null;
  constructor(readonly url: string) { }
  play(): Promise<void> { this.playing = true; return Promise.resolve(); }
  pause(): void { this.playing = false; }
}

const flush = (): Promise<void> => new Promise((done) => setTimeout(done, 0));

describe('pack tracks in the player', /** Register player cases. */ () => {
  let created: FakeAudio[];
  let steps: (() => void)[];

  beforeEach(/** A fresh page with no audio and a hand-driven fade timer. */ () => {
    created = [];
    steps = [];
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [
      { provide: MUSIC_LOCAL, useValue: [] },
      { provide: MUSIC_AUDIO, useValue: (url: string) => { const audio = new FakeAudio(url); created.push(audio); return audio; } },
      { provide: MUSIC_TICK, useValue: (step: () => void) => { steps.push(step); return () => { steps = steps.filter((s) => s !== step); }; } },
      { provide: MUSIC_STORAGE, useValue: null },
    ] });
  });

  it('chooses nothing for a scope while its pack listing is unknown, so the online host is never asked first', async () => {
    const music = TestBed.inject(MusicPlayer);
    music.offer('a', null);
    music.cue('a', 'exploration');
    music.activate();
    await flush();
    expect(created).toHaveLength(0);
    expect(music.status()).toBe('silent');
    music.offer('a', parseMusicListing(listing(), ADVENTURE, CAMPAIGN).tracks);
    await flush();
    expect(created.map((audio) => audio.url)).toEqual([`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH}?adventureId=${ADVENTURE}`]);
    expect(created.some((audio) => audio.url.includes('wikimedia'))).toBe(false);
    // The crossfade to the pack's tension track ends with the old source at volume 0 before it is paused.
    const tense = parseMusicListing(listing({ tracks: [track(), track({ id: 'ford-stones', mood: 'tension', sha256: 'e'.repeat(64) })] }), ADVENTURE, CAMPAIGN);
    music.offer('a', tense.tracks);
    music.cue('a', 'tension');
    await flush();
    for (let i = 0; i < 40; i++) [...steps].forEach((step) => step());
    expect(created[0].playing).toBe(false);
    expect(created[0].volume).toBe(0);
    expect(created.at(-1)!.url).toContain(`/assets/${'e'.repeat(64)}?`);
    expect(created.at(-1)!.volume).toBe(0.5);
  });

  it('falls back to the reviewed catalog once a scope is known to have no pack music', async () => {
    const music = TestBed.inject(MusicPlayer);
    music.offer('a', null);
    music.cue('a', 'exploration');
    music.activate();
    music.offer('a', []);
    await flush();
    expect(created.map((audio) => audio.url)).toEqual([mediaUrl(recordingFor('exploration')!)]);
  });

  it('prefers the scope pack track, keeps it for that scope only and restarts nothing on a repeated offer', async () => {
    const music = TestBed.inject(MusicPlayer);
    const pack = parseMusicListing(listing(), ADVENTURE, CAMPAIGN).tracks;
    music.offer('a', pack);
    music.cue('a', 'exploration');
    music.activate();
    await flush();
    expect(created.map((audio) => audio.url)).toEqual([mediaUrl(pack[0])]);
    expect(music.current()?.id).toBe('pack:ford-dusk');
    expect(music.packTracks()).toEqual(pack);
    music.offer('a', parseMusicListing(listing(), ADVENTURE, CAMPAIGN).tracks);
    await flush();
    expect(created).toHaveLength(1);
    // Another scope without pack music falls back to the reviewed catalog, and the first scope's track is silenced at once.
    music.cue('b', 'exploration');
    await flush();
    expect(created[0].playing).toBe(false);
    expect(music.packTracks()).toEqual([]);
    expect(created.at(-1)!.url).toBe(mediaUrl(recordingFor('exploration')!));
  });

  it('pauses every source and stops its fade timer when its injector is destroyed, so nothing outlives a test', async () => {
    const music = TestBed.inject(MusicPlayer);
    music.offer('a', parseMusicListing(listing(), ADVENTURE, CAMPAIGN).tracks);
    music.cue('a', 'exploration');
    music.activate();
    await flush();
    expect(created[0].playing).toBe(true);
    expect(steps).toHaveLength(1);
    TestBed.resetTestingModule();
    expect(created.every((audio) => !audio.playing)).toBe(true);
    expect(steps).toHaveLength(0);
  });

  it('switches to the pack track when the listing arrives after the catalog track started', async () => {
    const music = TestBed.inject(MusicPlayer);
    music.cue('a', 'exploration');
    music.activate();
    await flush();
    expect(created[0].url).toBe(mediaUrl(recordingFor('exploration')!));
    music.offer('a', parseMusicListing(listing(), ADVENTURE, CAMPAIGN).tracks);
    await flush();
    expect(created.map((audio) => audio.url).at(-1)).toContain(`/assets/${HASH}?`);
    expect(music.current()?.id).toBe('pack:ford-dusk');
  });
});
