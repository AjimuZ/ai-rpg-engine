/**
 * Optional dynamic music controller (P17.2-B, QA-32): one current track chosen from the reviewed catalog by a
 * public mood, started only by a deliberate click, crossfaded, deduplicated and silent on any failure.
 *
 * Contract:
 * - Nothing is created or fetched before {@link MusicPlayer.activate} is called from a user gesture, so opening
 *   a story never contacts the audio host. The `enabled` preference is saved on the device, but playback after a
 *   reload needs a new click (`armed`), so a reconnect or reload never starts sound by itself.
 * - {@link MusicPlayer.cue} is idempotent: the same scope and mood, or a mood that maps to the track already
 *   playing, restarts nothing. A different scope drops the previous scope's track at once (a stale cue is never
 *   crossfaded into the new campaign).
 * - At most two sources are audible: the active track and one fading-out track; a newer transition ends the
 *   fading one immediately. A candidate that is still loading is silent and is discarded if a newer cue or a
 *   pause arrives first.
 * - A refused, failed or removed source leaves the current valid track playing (or silence) and records
 *   `blocked` (the browser denied playback) or `unavailable`; nothing is retried silently and no other
 *   recording is substituted. {@link MusicPlayer.activate} is the explicit retry.
 * - Music never affects the story: no method throws into rendering and nothing here reads or sends story text.
 */
import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { admitLocal, Mood, mediaUrl, MUSIC_CATALOG, MusicRecording, recordingFor } from './music-catalog';

/** The part of an audio element this controller uses; a test replaces the real element. */
export interface MusicAudio {
  loop: boolean;
  volume: number;
  play(): Promise<void> | void;
  pause(): void;
  /** Typed as the DOM handler so a real `HTMLAudioElement` is assignable; the player ignores the event. */
  onerror: OnErrorEventHandler;
}

/** Creates a player for one reviewed media address. Called only after the player activated music. */
export const MUSIC_AUDIO = new InjectionToken<(url: string) => MusicAudio>('Music audio factory', {
  providedIn: 'root', factory: () => (url: string) => new Audio(url),
});

/** Repeats `step` every `ms` milliseconds and returns a function that stops it; a test drives it by hand. */
export const MUSIC_TICK = new InjectionToken<(step: () => void, ms: number) => () => void>('Music fade timer', {
  providedIn: 'root', factory: () => (step: () => void, ms: number) => {
    const timer = setInterval(step, ms);
    return () => clearInterval(timer);
  },
});

/** Device storage of the music preference; null keeps it in memory only. */
export const MUSIC_STORAGE = new InjectionToken<{ getItem(key: string): string | null; setItem(key: string, value: string): void } | null>(
  'Music preference storage', {
    providedIn: 'root', factory: /** Policy denial is represented by null, never thrown into rendering. */ () => {
      try { return globalThis.localStorage ?? null; } catch { return null; }
    },
  });

/**
 * Locally installed tracks offered beside the online catalog (P17.2-C). Each is admitted by `admitLocal` under the
 * same rights review as an online record; a refused one is dropped. Empty by default: no local file is bundled.
 */
export const MUSIC_LOCAL = new InjectionToken<readonly unknown[]>('Local music catalog', { providedIn: 'root', factory: () => [] });

/** Storage key of the preference record. */
export const MUSIC_KEY = 'engine.music.v1';
/** Length of a crossfade or fade to silence, and the spacing of its volume steps. */
export const FADE_MS = 2_000;
export const STEP_MS = 100;
/** Steps in one fade. */
export const FADE_STEPS = FADE_MS / STEP_MS;
/** Volume before the player chooses one. */
export const DEFAULT_VOLUME = 0.5;

/** What the panel reports: why there is or is not sound. */
export type MusicStatus = 'off' | 'needs-start' | 'blocked' | 'unavailable' | 'paused' | 'playing' | 'silent';

/** One audible or loading source and its fade position. */
interface Slot {
  readonly record: MusicRecording;
  readonly audio: MusicAudio;
  /** Fade position in whole steps, 0 (silent) to {@link FADE_STEPS} (full); integers avoid rounding drift. */
  level: number;
  goal: 0 | 1;
}

/** Owns the single music session of the page; see the module comment for its guarantees. */
@Injectable({ providedIn: 'root' })
export class MusicPlayer {
  private readonly factory = inject(MUSIC_AUDIO);
  private readonly tick = inject(MUSIC_TICK);
  private readonly storage = inject(MUSIC_STORAGE);
  /**
   * Admitted local tracks first, then the reviewed online ones: a local copy needs no network, and both pass the same
   * rights review, loop, crossfade and failure rules. A mood without either has silence.
   */
  readonly catalog: readonly MusicRecording[] = [
    ...inject(MUSIC_LOCAL).map(admitLocal).filter((record): record is MusicRecording => record !== null),
    ...MUSIC_CATALOG,
  ];
  /** Saved preference: the player turned music on at some point and has not turned it off. */
  readonly enabled = signal(false);
  /** True only after a click in this page load; playback needs both this and {@link enabled}. */
  readonly armed = signal(false);
  readonly paused = signal(false);
  readonly muted = signal(false);
  /** Music volume from 0 to 1. */
  readonly volume = signal(DEFAULT_VOLUME);
  /**
   * Effects volume from 0 to 1, kept apart from the music volume and saved with it. No sound effect exists yet, so
   * nothing reads it; it is the control a later effects player must obey.
   */
  readonly effectsVolume = signal(DEFAULT_VOLUME);
  /** Tracks the current scope's pack ships (P17.2-D); empty for another scope or a pack without music. */
  readonly packTracks = signal<readonly MusicRecording[]>([]);
  /** The recording that is audible or fading in; null in silence. */
  readonly current = signal<MusicRecording | null>(null);
  /** Recordings that actually started for the current scope, in first-played order and without repeats (for credits). */
  readonly used = signal<readonly MusicRecording[]>([]);
  readonly problem = signal<'blocked' | 'unavailable' | null>(null);
  readonly status = computed((): MusicStatus => {
    if (!this.enabled()) return 'off';
    if (!this.armed()) return 'needs-start';
    if (this.problem()) return this.problem()!;
    if (this.paused()) return 'paused';
    return this.current() ? 'playing' : 'silent';
  });

  private scope: string | null = null;
  /** Scope whose pack tracks were offered last; they apply only while that scope is current. */
  private packScope: string | null = null;
  /** Null while that scope's pack listing is still being read: no track is chosen for it until it is known. */
  private offeredTracks: readonly MusicRecording[] | null = [];
  private mood: Mood | null = null;
  private wanted: MusicRecording | null = null;
  private active: Slot | null = null;
  private outgoing: Slot | null = null;
  private pending: Slot | null = null;
  /** Invalidates the callbacks of an older load. */
  private generation = 0;
  private stopTick: (() => void) | null = null;

  constructor() {
    const saved = this.read();
    this.enabled.set(saved.enabled);
    this.muted.set(saved.muted);
    this.volume.set(saved.volume);
    this.effectsVolume.set(saved.effects);
    // When its injector is destroyed (a test module reset, an application teardown) the player pauses every source and
    // stops its fade timer, so no element or interval outlives it and keeps a test run from exiting.
    inject(DestroyRef).onDestroy(/** Release audio and timer. */ () => { this.silence(); this.scope = null; this.mood = null; this.wanted = null; });
  }

  /**
   * Offer the mood of the current public state for a scope (campaign).
   *
   * Repeating a scope and mood, or a mood that maps to the track already current, does nothing. A new scope
   * silences the previous one first. While music is off, paused or not started this only remembers the choice.
   */
  cue(scope: string, mood: Mood): void {
    if (scope !== this.scope) {
      this.silence();
      this.scope = scope;
      this.mood = null;
      this.problem.set(null);
      this.used.set([]);
      this.packTracks.set(this.packScope === scope ? this.offeredTracks ?? [] : []);
    }
    if (mood === this.mood) return;
    this.mood = mood;
    this.choose();
  }

  /**
   * Offer the tracks a scope's pack ships (P17.2-D). They are preferred over local and online records of the same mood
   * while that scope is current, and dropped when another scope is cued. `null` means the scope's listing is still being
   * read: until it is known nothing is chosen for that scope, so a switch never fetches a catalog track (possibly from the
   * online host) that the pack's own track would replace a moment later. Offering the same tracks again restarts nothing;
   * a different set re-chooses the current mood's track under the usual crossfade and failure rules. A scope that is cued
   * without any offer plays the local and online records.
   */
  offer(scope: string, tracks: readonly MusicRecording[] | null): void {
    const same = this.packScope === scope && JSON.stringify(this.offeredTracks) === JSON.stringify(tracks);
    this.packScope = scope;
    this.offeredTracks = tracks;
    if (same || scope !== this.scope) return;
    this.packTracks.set(tracks ?? []);
    if (this.mood !== null) this.choose();
  }

  /** Pick the current mood's track from pack, local and online records, and move to it when music may sound. */
  private choose(): void {
    if (this.mood === null) return;
    if (this.packScope === this.scope && this.offeredTracks === null) {
      // The pack listing of this scope is not known yet: stay silent rather than start a track it may replace.
      this.wanted = null;
      return;
    }
    this.wanted = recordingFor(this.mood, [...this.packTracks(), ...this.catalog]) ?? null;
    // A denied playback is not retried by a mood change: only a new click (activate) tries again.
    if (this.playable() && this.problem() !== 'blocked') this.transition(this.wanted);
  }

  /** The player left the scope's screen: stop its music and forget its cue so a stale cue cannot come back. */
  leave(scope: string): void {
    if (scope !== this.scope) return;
    this.silence();
    this.scope = null;
    this.mood = null;
    this.wanted = null;
  }

  /**
   * Turn music on and start it. Must be called from a click or key handler: that gesture is what lets the browser
   * allow playback. Also the explicit retry after `blocked` or `unavailable`.
   */
  activate(): void {
    this.enabled.set(true);
    this.armed.set(true);
    this.paused.set(false);
    this.problem.set(null);
    this.save();
    this.transition(this.wanted);
  }

  /** Turn music off and silence it at once; the preference is saved. */
  disable(): void {
    this.enabled.set(false);
    this.armed.set(false);
    this.paused.set(false);
    this.problem.set(null);
    this.silence();
    this.save();
  }

  /** Pause playback in place; a loading candidate is discarded. */
  pause(): void {
    if (this.paused()) return;
    this.paused.set(true);
    this.cancelPending();
    for (const slot of [this.active, this.outgoing]) if (slot) this.halt(slot);
  }

  /** Continue after {@link pause} (a click), or start the wanted track when none was active. */
  resume(): void {
    if (!this.paused()) return;
    this.paused.set(false);
    const slot = this.active;
    // A mood that arrived while paused wins: resuming plays what the situation now asks for.
    if (!slot || slot.record.id !== this.wanted?.id) { this.transition(this.wanted); return; }
    this.drop(this.outgoing);
    this.outgoing = null;
    try {
      void Promise.resolve(slot.audio.play()).catch(/** The browser refused: report it, do not retry. */ () => this.stopActive('blocked'));
    } catch { this.stopActive('unavailable'); }
  }

  setMuted(muted: boolean): void { this.muted.set(muted); this.save(); this.applyVolume(); }

  /** Set the music volume; values are clamped to 0..1 and a non-number is ignored. */
  setVolume(value: number): void {
    if (!Number.isFinite(value)) return;
    this.volume.set(Math.min(1, Math.max(0, value)));
    this.save();
    this.applyVolume();
  }

  /** Set the effects volume; clamped to 0..1, a non-number is ignored. */
  setEffectsVolume(value: number): void {
    if (!Number.isFinite(value)) return;
    this.effectsVolume.set(Math.min(1, Math.max(0, value)));
    this.save();
  }

  /** True when a track may sound now. */
  private playable(): boolean { return this.enabled() && this.armed() && !this.paused(); }

  /** Move to `wanted` (null fades to silence); starts synchronously so the click gesture still counts. */
  private transition(wanted: MusicRecording | null): void {
    if (!this.playable()) return;
    this.cancelPending();
    this.drop(this.outgoing);
    this.outgoing = null;
    if (wanted === null) { this.demote(); return; }
    if (this.active?.record.id === wanted.id) return;
    const generation = ++this.generation;
    try {
      const audio = this.factory(mediaUrl(wanted));
      audio.loop = true;
      audio.volume = 0;
      const slot: Slot = { record: wanted, audio, level: 0, goal: 1 };
      audio.onerror = () => this.lost(slot, generation);
      this.pending = slot;
      void Promise.resolve(audio.play()).then(
        /** Loaded and playing silently: fade it in unless a newer cue replaced it. */ () => {
          if (generation !== this.generation || this.pending !== slot) { this.drop(slot); return; }
          this.pending = null;
          this.promote(slot);
        },
        /** Refused or failed: keep what plays now and say why. */ (error: unknown) => {
          this.drop(slot);
          if (generation === this.generation && this.pending === slot) {
            this.pending = null;
            this.fail((error as { name?: string } | null)?.name === 'NotAllowedError' ? 'blocked' : 'unavailable');
          }
        });
    } catch {
      this.pending = null;
      this.fail('unavailable');
    }
  }

  /** The candidate becomes the current track and the previous one fades out. */
  private promote(slot: Slot): void {
    this.demote();
    this.active = slot;
    this.current.set(slot.record);
    if (!this.used().some((record) => record.id === slot.record.id)) this.used.set([...this.used(), slot.record]);
    this.problem.set(null);
    this.applyVolume();
    this.ramp();
  }

  /** Start fading the active track out; it stays audible only as the single outgoing source. */
  private demote(): void {
    this.drop(this.outgoing);
    this.outgoing = null;
    if (this.active) {
      this.active.goal = 0;
      this.outgoing = this.active;
      this.active = null;
      this.ramp();
    }
    this.current.set(null);
  }

  /** One source failed after it started (for example removed media), or a loading one raised an error. */
  private lost(slot: Slot, generation: number): void {
    if (this.pending === slot && generation === this.generation) {
      this.pending = null;
      this.generation++;
      this.drop(slot);
      this.fail('unavailable');
    } else if (this.active === slot) {
      this.stopActive('unavailable');
    } else if (this.outgoing === slot) {
      this.drop(slot);
      this.outgoing = null;
    }
  }

  private stopActive(kind: 'blocked' | 'unavailable'): void {
    this.drop(this.active);
    this.active = null;
    this.current.set(null);
    this.fail(kind);
  }

  private fail(kind: 'blocked' | 'unavailable'): void { this.problem.set(kind); }

  /** Stop everything at once without a fade (scope change, leaving, turning off). */
  private silence(): void {
    this.cancelPending();
    for (const slot of [this.active, this.outgoing]) this.drop(slot);
    this.active = null;
    this.outgoing = null;
    this.current.set(null);
    this.stopTick?.();
    this.stopTick = null;
  }

  private cancelPending(): void {
    this.generation++;
    this.drop(this.pending);
    this.pending = null;
  }

  private halt(slot: Slot): void { try { slot.audio.pause(); } catch { /* A source that cannot pause is already stopped. */ } }

  private drop(slot: Slot | null): void {
    if (!slot) return;
    slot.audio.onerror = null;
    this.halt(slot);
  }

  /** Apply the music volume and each source's fade position to its element. */
  private applyVolume(): void {
    const level = this.muted() ? 0 : this.volume();
    for (const slot of [this.active, this.outgoing]) if (slot) slot.audio.volume = Math.min(1, Math.max(0, (slot.level / FADE_STEPS) * level));
  }

  /** Run the fade timer until the active track is fully in and the outgoing one is gone. */
  private ramp(): void {
    if (this.stopTick) return;
    this.stopTick = this.tick(() => {
      for (const slot of [this.active, this.outgoing]) {
        if (slot) slot.level = slot.goal === 1 ? Math.min(FADE_STEPS, slot.level + 1) : Math.max(0, slot.level - 1);
      }
      if (this.outgoing && this.outgoing.level === 0) {
        // The last step is silence itself: set it before pausing, so the source never stops at an audible level.
        this.outgoing.audio.volume = 0;
        this.drop(this.outgoing);
        this.outgoing = null;
      }
      this.applyVolume();
      if (!this.outgoing && (!this.active || this.active.level === FADE_STEPS)) { this.stopTick?.(); this.stopTick = null; }
    }, STEP_MS);
  }

  /** Persisted preference; corrupt, foreign or out-of-range data yields the defaults. */
  private read(): { enabled: boolean; muted: boolean; volume: number; effects: number } {
    const defaults = { enabled: false, muted: false, volume: DEFAULT_VOLUME, effects: DEFAULT_VOLUME };
    try {
      const raw = this.storage?.getItem(MUSIC_KEY) ?? null;
      if (raw === null || raw.length > 200) return defaults;
      const value = JSON.parse(raw) as Record<string, unknown>;
      const volume = value['volume'];
      // A record saved before the effects volume existed has no `effects`; it keeps the default for it.
      const effects = value['effects'] === undefined ? DEFAULT_VOLUME : value['effects'];
      if (value['version'] !== 1 || typeof value['enabled'] !== 'boolean' || typeof value['muted'] !== 'boolean'
        || typeof volume !== 'number' || !Number.isFinite(volume) || volume < 0 || volume > 1
        || typeof effects !== 'number' || !Number.isFinite(effects) || effects < 0 || effects > 1) return defaults;
      return { enabled: value['enabled'], muted: value['muted'], volume, effects };
    } catch { return defaults; }
  }

  /** Save the preference; a storage failure only means it is not remembered. */
  private save(): void {
    try {
      this.storage?.setItem(MUSIC_KEY, JSON.stringify({ version: 1, enabled: this.enabled(), muted: this.muted(), volume: this.volume(), effects: this.effectsVolume() }));
    } catch { /* The preference is simply not remembered. */ }
  }
}
