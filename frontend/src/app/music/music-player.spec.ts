/**
 * Optional music controller and mood mapping (P17.2-B, QA-32) against a fake audio element and a hand-driven fade timer,
 * so no audio host is contacted and no sound is made. This proves the controller's rules, not real browser playback.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { CombatView } from '../campaign/campaign-protocol';
import { MUSIC_CATALOG, mediaUrl, recordingFor } from './music-catalog';
import { moodOf } from './music-mood';
import { FADE_STEPS, MUSIC_AUDIO, MUSIC_KEY, MUSIC_STORAGE, MUSIC_TICK, MusicAudio, MusicPlayer } from './music-player';

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
  function player(): MusicPlayer {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [
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
    expect(JSON.parse(storage.values.get(MUSIC_KEY)!)).toEqual({ version: 1, enabled: true, muted: false, volume: 0.3 });
    const second = player();
    expect(second.enabled()).toBe(true);
    expect(second.volume()).toBe(0.3);
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
