/**
 * Optional music controller and mood mapping (P17.2-B, QA-32) against a fake audio element and a hand-driven fade timer,
 * so no audio host is contacted and no sound is made. This proves the controller's rules, not real browser playback.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { CombatView } from '../campaign/campaign-protocol';
import { admitLocal, creditText, MUSIC_CATALOG, mediaUrl, recordingFor } from './music-catalog';
import { DEFAULT_MOOD_POLICY, moodOf, parseMoodPolicy } from './music-mood';
import { FADE_STEPS, MUSIC_AUDIO, MUSIC_KEY, MUSIC_LOCAL, MUSIC_STORAGE, MUSIC_TICK, MusicAudio, MusicPlayer } from './music-player';

/** A complete, admissible local record for the tension mood. */
const LOCAL = {
  id: 'local-tension', title: 'Local Tension', creator: 'A. Composer', mood: 'tension',
  sourcePage: 'https://commons.wikimedia.org/wiki/File:Local_tension.ogg', mediaFile: 'Local_tension.ogg',
  licenseId: 'CC-BY-4.0', licenseName: 'Creative Commons Attribution 4.0 International',
  licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', notices: 'Attribution required.',
  modifications: 'Trimmed to 60 seconds.', verifiedOn: '2026-11-21', localFile: 'local-tension.ogg', localHash: 'a'.repeat(64),
};

/** A fake element that records what the controller does with it. */
class FakeAudio implements MusicAudio {
  loop = false;
  volume = 1;
  playing = false;
  onerror: ((event: unknown) => void) | null = null;
  constructor(readonly url: string, private readonly outcome: () => string | null) { }
  play(): Promise<void> {
    const failure = this.outcome();
    if (failure) return Promise.reject({ name: failure });
    this.playing = true;
    return Promise.resolve();
  }
  pause(): void { this.playing = false; }
}

class Memory {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

/** Let promise callbacks of a started track run. */
const flush = (): Promise<void> => new Promise((done) => setTimeout(done, 0));

describe('music player', /** Register the controller rules. */ () => {
  let created: FakeAudio[];
  let fail: (url: string) => string | null;
  let steps: (() => void)[];
  let storage: Memory;

  /** Create a fresh injector, as a new page load would. */
  function player(local: readonly unknown[] = []): MusicPlayer {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [
      { provide: MUSIC_LOCAL, useValue: local },
      { provide: MUSIC_AUDIO, useValue: (url: string) => { const audio = new FakeAudio(url, () => fail(url)); created.push(audio); return audio; } },
      { provide: MUSIC_TICK, useValue: (step: () => void) => { steps.push(step); return () => { steps = steps.filter((s) => s !== step); }; } },
      { provide: MUSIC_STORAGE, useValue: storage },
    ] });
    return TestBed.inject(MusicPlayer);
  }
  /** Run every pending fade step `count` times. */
  const advance = (count: number): void => { for (let i = 0; i < count; i++) [...steps].forEach((step) => step()); };
  const sounding = (): FakeAudio[] => created.filter((audio) => audio.playing);

  beforeEach(/** Each test starts with no audio, no timer and empty storage. */ () => {
    created = []; steps = []; storage = new Memory(); fail = () => null;
  });

  it('creates no audio and contacts no host until the player turns music on', async () => {
    const music = player();
    music.cue('a', 'exploration');
    await flush();
    expect(created).toHaveLength(0);
    expect(music.status()).toBe('off');
    music.activate();
    await flush();
    expect(created.map((audio) => audio.url)).toEqual([mediaUrl(recordingFor('exploration')!)]);
    expect(music.status()).toBe('playing');
    expect(created[0].loop).toBe(true);
  });

  it('deduplicates repeated cues and starts a fresh track only after silence', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    music.cue('a', 'exploration');
    await flush();
    music.cue('a', 'dialogue');
    music.cue('a', 'rest');
    await flush();
    // Dialogue and rest have no reviewed track: the current one fades out once, and nothing new is created.
    expect(created).toHaveLength(1);
    music.cue('a', 'exploration');
    await flush();
    expect(created).toHaveLength(2);
  });

  it('crossfades over the fade length with at most two audible sources and the music volume applied', async () => {
    const music = player();
    music.setVolume(0.8);
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    advance(FADE_STEPS);
    expect(created[0].volume).toBeCloseTo(0.8);
    music.cue('a', 'tension');
    await flush();
    expect(sounding()).toHaveLength(2);
    advance(FADE_STEPS / 2);
    expect(created[0].volume).toBeCloseTo(0.4);
    expect(created[1].volume).toBeCloseTo(0.4);
    advance(FADE_STEPS / 2);
    expect(sounding()).toEqual([created[1]]);
    expect(created[1].volume).toBeCloseTo(0.8);
    expect(steps).toHaveLength(0);
    expect(music.current()?.id).toBe('darkest-child');
  });

  it('fades to silence for a mood without a reviewed recording and substitutes nothing', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    music.cue('a', 'combat');
    await flush();
    expect(music.current()).toBeNull();
    expect(music.status()).toBe('silent');
    advance(FADE_STEPS);
    expect(sounding()).toHaveLength(0);
    expect(created).toHaveLength(1);
  });

  it('drops a previous scope\'s track at once instead of crossfading it into the next scope', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'tension');
    await flush();
    music.cue('b', 'exploration');
    expect(created[0].playing).toBe(false);
    await flush();
    expect(sounding().map((audio) => audio.url)).toEqual([mediaUrl(recordingFor('exploration')!)]);
    music.leave('b');
    expect(sounding()).toHaveLength(0);
    music.cue('b', 'exploration');
    await flush();
    expect(sounding()).toHaveLength(1);
  });

  it('reports denied playback as inactive and retries only on a new click', async () => {
    fail = () => 'NotAllowedError';
    const music = player();
    music.cue('a', 'exploration');
    music.activate();
    await flush();
    expect(music.status()).toBe('blocked');
    music.cue('a', 'tension');
    await flush();
    expect(created).toHaveLength(1);
    fail = () => null;
    music.activate();
    await flush();
    expect(music.status()).toBe('playing');
    expect(music.current()?.id).toBe('darkest-child');
  });

  it('keeps the current track when a new source is unavailable and never retries by itself', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    fail = (url) => url.includes('Darkest') ? 'NotSupportedError' : null;
    music.cue('a', 'tension');
    await flush();
    expect(music.status()).toBe('unavailable');
    expect(music.current()?.id).toBe('wholesome');
    expect(sounding()).toHaveLength(1);
    expect(created).toHaveLength(2);
  });

  it('goes silent and reports it when the playing source fails later', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    created[0].onerror?.(new Event('error'));
    expect(music.status()).toBe('unavailable');
    expect(music.current()).toBeNull();
    expect(sounding()).toHaveLength(0);
  });

  it('saves the preference but needs a click again after a reload', async () => {
    const first = player();
    first.setVolume(0.3);
    first.activate();
    first.setEffectsVolume(0.8);
    expect(JSON.parse(storage.values.get(MUSIC_KEY)!)).toEqual({ version: 1, enabled: true, muted: false, volume: 0.3, effects: 0.8 });
    const second = player();
    expect(second.enabled()).toBe(true);
    expect(second.volume()).toBe(0.3);
    expect(second.effectsVolume()).toBe(0.8);
    second.cue('a', 'exploration');
    await flush();
    expect(created).toHaveLength(0);
    expect(second.status()).toBe('needs-start');
    second.activate();
    await flush();
    expect(created).toHaveLength(1);
  });

  it('ignores a corrupt saved preference', () => {
    storage.setItem(MUSIC_KEY, '{"version":1,"enabled":"yes","muted":false,"volume":7}');
    const music = player();
    expect(music.enabled()).toBe(false);
    expect(music.volume()).toBe(0.5);
  });

  it('pauses, resumes, mutes and turns off', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    advance(FADE_STEPS);
    music.pause();
    expect(music.status()).toBe('paused');
    expect(sounding()).toHaveLength(0);
    music.cue('a', 'tension');
    await flush();
    expect(created).toHaveLength(1);
    // The mood changed while paused, so resuming plays the track the situation now asks for.
    music.resume();
    await flush();
    expect(created).toHaveLength(2);
    expect(sounding()).toEqual([created[1]]);
    music.setMuted(true);
    expect(created[1].volume).toBe(0);
    music.disable();
    expect(sounding()).toHaveLength(0);
    expect(music.status()).toBe('off');
  });

  it('plays an admitted local track under the same loop, fade and failure rules, and drops an unreviewed one', async () => {
    const music = player([LOCAL, { ...LOCAL, id: 'bad', licenseUrl: 'https://creativecommons.org/licenses/by/3.0/' }]);
    expect(music.catalog.map((record) => record.id)).toEqual(['local-tension', 'wholesome', 'darkest-child']);
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    music.cue('a', 'tension');
    await flush();
    expect(created.map((audio) => audio.url)).toEqual([mediaUrl(recordingFor('exploration')!), '/assets/music/local-tension.ogg']);
    expect(created[1].loop).toBe(true);
    advance(FADE_STEPS);
    expect(sounding()).toEqual([created[1]]);
    expect(music.current()?.id).toBe('local-tension');
    // A removed local file ends in the same quiet failure as a removed online one and nothing stands in for it.
    created[1].onerror?.(new Event('error'));
    expect(music.status()).toBe('unavailable');
    expect(sounding()).toHaveLength(0);
    expect(created).toHaveLength(2);
  });

  it('keeps the music and effects volumes separate and saved', () => {
    const first = player();
    first.setVolume(0.2);
    first.setEffectsVolume(0.9);
    first.setEffectsVolume(Number.NaN);
    expect([first.volume(), first.effectsVolume()]).toEqual([0.2, 0.9]);
    const second = player();
    expect([second.volume(), second.effectsVolume()]).toEqual([0.2, 0.9]);
    // A preference saved before the effects volume existed still loads, with the default effects volume.
    storage.setItem(MUSIC_KEY, '{"version":1,"enabled":true,"muted":false,"volume":0.4}');
    const older = player();
    expect([older.volume(), older.effectsVolume()]).toEqual([0.4, 0.5]);
  });

  it('lists each recording that actually played once, per scope', async () => {
    const music = player();
    music.activate();
    music.cue('a', 'exploration');
    await flush();
    music.cue('a', 'tension');
    await flush();
    music.cue('a', 'exploration');
    await flush();
    expect(music.used().map((record) => record.id)).toEqual(['wholesome', 'darkest-child']);
    music.cue('b', 'dialogue');
    expect(music.used()).toEqual([]);
  });
});

describe('music credits and local admission', /** Register the credit text and the local-track review. */ () => {
  it('states every credit field of a recording, including its disclosed modifications and check date', () => {
    const text = creditText(MUSIC_CATALOG[0]);
    for (const part of [MUSIC_CATALOG[0].title, MUSIC_CATALOG[0].creator, MUSIC_CATALOG[0].sourcePage, MUSIC_CATALOG[0].licenseUrl,
      MUSIC_CATALOG[0].notices, MUSIC_CATALOG[0].modifications, MUSIC_CATALOG[0].verifiedOn]) expect(text).toContain(part);
    expect(creditText(admitLocal(LOCAL)!)).toContain(`SHA-256 ${'a'.repeat(64)}`);
  });

  it('refuses a local record that lacks rights, names another host or file, or has no hash', () => {
    expect(admitLocal(LOCAL)).not.toBeNull();
    for (const bad of [
      { ...LOCAL, creator: '' }, { ...LOCAL, licenseId: 'CC-BY-NC-4.0' }, { ...LOCAL, mood: 'party' },
      { ...LOCAL, sourcePage: 'https://example.com/a.ogg' }, { ...LOCAL, sourcePage: 'http://commons.wikimedia.org/a.ogg' },
      { ...LOCAL, localFile: '../secret.ogg' }, { ...LOCAL, localFile: 'a.exe' }, { ...LOCAL, localHash: 'abc' },
      { ...LOCAL, localFile: undefined }, { ...LOCAL, verifiedOn: 'yesterday' }, null, 'text',
    ]) expect(admitLocal(bad)).toBeNull();
  });

  it('accepts a pack mood policy of known public states and moods only', () => {
    expect(parseMoodPolicy({})).toEqual(DEFAULT_MOOD_POLICY);
    expect(parseMoodPolicy({ fight: 'tension', lost: 'rest' })).toEqual({ ...DEFAULT_MOOD_POLICY, fight: 'tension', lost: 'rest' });
    const view = { encounter: { outcome: null }, offers: [] } as unknown as CombatView;
    expect(moodOf(view, parseMoodPolicy({ fight: 'tension' })!)).toBe('tension');
    // A hidden-state key, an unknown mood and a non-object are refused whole, never repaired.
    expect(parseMoodPolicy({ hiddenEnemy: 'combat' })).toBeNull();
    expect(parseMoodPolicy({ fight: 'party' })).toBeNull();
    expect(parseMoodPolicy({ fight: 3 })).toBeNull();
    expect(parseMoodPolicy(['combat'])).toBeNull();
    expect(parseMoodPolicy(null)).toBeNull();
  });
});

describe('music mood', /** Register the public-state mapping. */ () => {
  const encounter = (outcome: string | null) => ({ encounter: { outcome: outcome ? { outcome } : null }, offers: [] }) as unknown as CombatView;
  const offered = { encounter: null, offers: [{ templateId: 'x' }] } as unknown as CombatView;

  it('maps only the public projection to a mood', /** Fight, result, offered encounter and nothing. */ () => {
    expect(moodOf(null)).toBe('exploration');
    expect(moodOf({ encounter: null, offers: [] } as unknown as CombatView)).toBe('exploration');
    expect(moodOf(offered)).toBe('tension');
    expect(moodOf(encounter(null))).toBe('combat');
    expect(moodOf(encounter('VICTORY'))).toBe('victory');
    expect(moodOf(encounter('PLAYER_SURRENDER'))).toBe('defeat');
    expect(moodOf(encounter('ESCAPE'))).toBe('exploration');
  });

  it('keeps one reviewed record per admitted license and a media address on the approved host only', /** Catalog guard. */ () => {
    for (const record of MUSIC_CATALOG) {
      expect(mediaUrl(record).startsWith('https://commons.wikimedia.org/wiki/Special:FilePath/')).toBe(true);
      expect(['CC0-1.0', 'CC-BY-3.0', 'CC-BY-4.0']).toContain(record.licenseId);
      expect(record.creator).not.toBe('');
    }
  });
});
