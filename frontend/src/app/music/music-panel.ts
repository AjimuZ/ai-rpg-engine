/**
 * The optional music panel of a playthrough (P17.2-B): an explicit opt-in, pause/mute/volume, the current track with its
 * creator and license links, and a searchable credits list of the whole reviewed catalog.
 *
 * The panel offers the playthrough's public mood to {@link MusicPlayer} and nothing else; it reads no story text. Music is off
 * until the player clicks the button, and every state has a text status, so the page reads and plays the same without sound.
 * Catalog data (titles, creators, license names, notices) is shown verbatim in English, marked with `lang="en"`.
 */
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { creditText, Mood, MUSIC_CATALOG, MusicRecording } from './music-catalog';
import { MusicPlayer, MusicStatus } from './music-player';

/** Show the music controls for one campaign scope and mood. */
@Component({
  selector: 'app-music', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="music" data-music aria-labelledby="music-title">
      <h3 id="music-title">{{ text.title }}</h3>
      <p class="muted">{{ text.help }}</p>
      <p role="status" data-music-status>{{ statusText(player.status()) }}</p>
      <p>
        @switch (player.status()) {
          @case ('off') { <button type="button" data-music-on (click)="player.activate()">{{ text.on }}</button> }
          @case ('needs-start') { <button type="button" data-music-start (click)="player.activate()">{{ text.start }}</button> }
          @case ('blocked') { <button type="button" data-music-retry (click)="player.activate()">{{ text.retry }}</button> }
          @case ('unavailable') { <button type="button" data-music-retry (click)="player.activate()">{{ text.retry }}</button> }
          @case ('paused') { <button type="button" data-music-resume (click)="player.resume()">{{ text.resume }}</button> }
          @default { <button type="button" data-music-pause (click)="player.pause()">{{ text.pause }}</button> }
        }
        @if (player.enabled()) {
          <button type="button" data-music-mute [attr.aria-pressed]="player.muted()" (click)="player.setMuted(!player.muted())">{{ text.mute }}</button>
          <button type="button" data-music-off (click)="player.disable()">{{ text.off }}</button>
        }
      </p>
      @if (player.enabled()) {
        <p>
          <label for="music-volume">{{ text.volume }}</label>
          <input id="music-volume" type="range" min="0" max="100" step="5" [value]="percent()" (input)="volume($event)" data-music-volume>
        </p>
        <p>
          <label for="music-effects-volume">{{ text.effectsVolume }}</label>
          <input id="music-effects-volume" type="range" min="0" max="100" step="5" [value]="effectsPercent()" (input)="effects($event)" data-music-effects-volume>
        </p>
      }
      @if (player.current(); as track) {
        <dl data-music-track>
          <dt>{{ text.track }}</dt><dd lang="en" data-music-title>{{ track.title }}</dd>
          <dt>{{ text.creator }}</dt><dd lang="en">{{ track.creator }}</dd>
          <dt>{{ text.license }}</dt><dd><a [href]="track.licenseUrl" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer" lang="en" data-music-license>{{ track.licenseName }}</a></dd>
          <dt>{{ text.source }}</dt><dd><a [href]="track.sourcePage" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer" lang="en" data-music-source>{{ track.title }}</a></dd>
          <dt>{{ text.notices }}</dt><dd lang="en" data-music-notices>{{ track.notices }}</dd>
          <dt>{{ text.modifications }}</dt><dd lang="en" data-music-modifications>{{ track.modifications }}</dd>
          <dt>{{ text.verified }}</dt><dd data-music-verified>{{ track.verifiedOn }}</dd>
        </dl>
      }
      <details data-music-credits>
        <summary>{{ text.credits }}</summary>
        <p>
          <label for="music-credits-filter">{{ text.search }}</label>
          <input id="music-credits-filter" type="search" [value]="filter()" (input)="search($event)" data-music-filter>
        </p>
        <ul>
          @for (record of credits(); track record.id) {
            <li [attr.data-music-credit]="record.id">
              <span lang="en">{{ record.title }} — {{ record.creator }}.</span>
              <a [href]="record.licenseUrl" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer" lang="en">{{ record.licenseName }}</a>
              <a [href]="record.sourcePage" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">{{ text.source }}</a>
              <span lang="en">{{ record.notices }} {{ record.modifications }}</span>
            </li>
          } @empty { <li data-music-none>{{ text.none }}</li> }
        </ul>
      </details>
      <details data-music-used>
        <summary>{{ text.usedTitle }}</summary>
        @if (player.used().length > 0) {
          <textarea readonly rows="8" lang="en" [attr.aria-label]="text.usedTitle" data-music-used-credits [value]="usedCredits()"></textarea>
          <p><button type="button" data-music-download (click)="download()">{{ text.download }}</button></p>
        } @else { <p data-music-used-none>{{ text.usedNone }}</p> }
      </details>
    </section>
  `,
})
export class MusicPanel {
  /** Identity of the playthrough (adventure and campaign); a change of it silences the previous scope's music. */
  readonly scope = input.required<string>();
  /** Mood of the playthrough's current public state. */
  readonly mood = input.required<Mood>();
  readonly player = inject(MusicPlayer);
  readonly filter = signal('');
  readonly text = {
    title: $localize`:@@music.title:Music`,
    help: $localize`:@@music.help:Optional background music follows the public situation. Nothing is loaded from the internet until you turn it on, and the story is the same without it.`,
    on: $localize`:@@music.on:Turn music on`,
    off: $localize`:@@music.off:Turn music off`,
    start: $localize`:@@music.start:Start music`,
    retry: $localize`:@@music.retry:Try music again`,
    pause: $localize`:@@music.pause:Pause music`,
    resume: $localize`:@@music.resume:Resume music`,
    mute: $localize`:@@music.mute:Mute music`,
    volume: $localize`:@@music.volume:Music volume`,
    track: $localize`:@@music.track:Track`,
    creator: $localize`:@@music.creator:Creator`,
    license: $localize`:@@music.license:License`,
    source: $localize`:@@music.source:Source page`,
    credits: $localize`:@@music.credits.title:Music credits`,
    search: $localize`:@@music.credits.search:Search credits`,
    none: $localize`:@@music.credits.none:No credit matches your search.`,
    effectsVolume: $localize`:@@music.effectsVolume:Effects volume`,
    notices: $localize`:@@music.notices:Notices`,
    modifications: $localize`:@@music.modifications:Changes made`,
    verified: $localize`:@@music.verified:Rights checked`,
    usedTitle: $localize`:@@music.used.title:Credits of the music played in this visit`,
    usedNone: $localize`:@@music.used.none:No track has played in this visit yet.`,
    download: $localize`:@@music.used.download:Download these credits`,
  };
  private readonly statuses: Readonly<Record<MusicStatus, string>> = {
    off: $localize`:@@music.status.off:Music is off.`,
    'needs-start': $localize`:@@music.status.needsStart:Music is on but silent until you start it in this visit.`,
    blocked: $localize`:@@music.status.blocked:The browser did not allow playback. Use the button to try again.`,
    unavailable: $localize`:@@music.status.unavailable:The track could not be loaded. The story is unchanged.`,
    paused: $localize`:@@music.status.paused:Music is paused.`,
    playing: $localize`:@@music.status.playing:Music is playing.`,
    silent: $localize`:@@music.status.silent:No reviewed track fits this moment, so the music is silent.`,
  };
  private lastScope: string | null = null;

  /** Offer the mood to the player while the panel lives and silence this scope when it goes away. */
  constructor() {
    effect(/** Re-offer on a changed scope or mood; the player ignores a repeat. */ () => {
      const scope = this.scope();
      const mood = this.mood();
      untracked(/** Offering a cue reads player state that must not retrigger this effect. */ () => {
        this.lastScope = scope;
        this.player.cue(scope, mood);
      });
    });
    inject(DestroyRef).onDestroy(/** Leaving the playthrough stops its music. */ () => { if (this.lastScope !== null) this.player.leave(this.lastScope); });
  }

  statusText(status: MusicStatus): string { return this.statuses[status]; }

  /** Volume as the slider's 0-100 value. */
  percent(): number { return Math.round(this.player.volume() * 100); }

  /** Effects volume as the slider's 0-100 value. */
  effectsPercent(): number { return Math.round(this.player.effectsVolume() * 100); }

  effects(event: Event): void { this.player.setEffectsVolume(Number((event.target as HTMLInputElement).value) / 100); }

  /** The complete credits of the recordings that played in this scope, one block per recording; empty when none did. */
  usedCredits(): string { return this.player.used().map(creditText).join('\n\n'); }

  /** Save {@link usedCredits} as a text file; the file is built in the page and nothing is uploaded. */
  download(): void {
    const url = URL.createObjectURL(new Blob([this.usedCredits(), '\n'], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'music-credits.txt';
    link.click();
    URL.revokeObjectURL(url);
  }

  volume(event: Event): void { this.player.setVolume(Number((event.target as HTMLInputElement).value) / 100); }

  search(event: Event): void { this.filter.set((event.target as HTMLInputElement).value); }

  /** Catalog records whose credit text contains the filter, ignoring case; all of them for an empty filter. */
  credits(): readonly MusicRecording[] {
    const needle = this.filter().trim().toLowerCase();
    return MUSIC_CATALOG.filter(/** A record matches on any visible credit field. */ (record) =>
      needle === '' || [record.title, record.creator, record.licenseName, record.notices, record.modifications].some((field) => field.toLowerCase().includes(needle)));
  }
}
