/**
 * Presentation cues of one playthrough (P05.7-B.2.1, docs/13 section 12): the scene, the pose and
 * expression and the optional line audio that the server announced, shown as text first.
 *
 * Every cue is a labelled text line, so the written account is complete when a cue is absent, when
 * an asset cannot be loaded and when the player never starts audio. Audio is opt-in: nothing is
 * created or fetched before the player activates a native button (Enter and Space included), one line
 * plays at a time, and a load failure leaves a text notice. Cue entrance animation runs only when the
 * browser reports that motion is allowed; an unknown preference counts as reduced motion. The component
 * never submits anything and never builds a URL other than {@link cueAssetPath}.
 */
import { ChangeDetectionStrategy, Component, DestroyRef, inject, InjectionToken, input, signal } from '@angular/core';
import { PresentationCue } from './campaign-protocol';
import { cueAssetPath } from './presentation-cues';

/** The part of an audio element this component uses; a test may replace the real element. */
export interface CueAudio {
  play(): Promise<void> | void;
  pause(): void;
  onended: ((event: Event) => void) | null;
  onerror: ((event: Event | string) => void) | null;
}

/** Creates a player for one relative asset path. Called only after the player asks for audio. */
export const CUE_AUDIO = new InjectionToken<(path: string) => CueAudio>('Line audio player factory', {
  providedIn: 'root', factory: () => (path: string) => new Audio(path),
});

/** Reduced-motion media query; the preference is read once and followed while the view lives. */
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)';

/** Playback state of one line cue. */
type AudioState = 'idle' | 'playing' | 'unavailable';

/** Show the cues of one scope; the parent supplies them in display order and hides nothing else. */
@Component({
  selector: 'app-campaign-cues', changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .cue-list li { margin-block: .5rem; }
    .cue-list [data-audio-status]:empty { display: none; }
    /* Entrance fades run only when the script confirmed that motion is allowed. */
    [data-motion='allowed'] .cue-list li { animation: cue-in 250ms ease-out; }
    @keyframes cue-in { from { opacity: 0; } to { opacity: 1; } }
    @media (prefers-reduced-motion: reduce) {
      .cue-list li { animation: none; transition: none; }
    }
  `,
  template: `
    @if (cues().length > 0) {
      <section class="cues" data-cues [attr.data-motion]="motion()" aria-labelledby="cues-title">
        <h3 id="cues-title" i18n="@@campaign.cues.title">Scene and voices</h3>
        <p class="muted" i18n="@@campaign.cues.help">These lines describe what the scene shows. The written account is complete without them, and audio plays only when you choose to start it.</p>
        <ol class="cue-list">
          @for (cue of cues(); track cue.deliveryId) {
            <li [attr.data-cue-kind]="cue.kind" [attr.data-delivery-id]="cue.deliveryId" [attr.data-actor-id]="cue.actorId">
              @switch (cue.kind) {
                @case ('SCENE') { <strong i18n="@@campaign.cues.scene">Scene</strong>: <span data-scene>{{ cue.sceneId ?? unnamed }}</span> }
                @case ('POSE') {
                  <strong i18n="@@campaign.cues.pose">Pose</strong>: <span data-pose>{{ cue.poseId ?? unnamed }}</span>
                  @if (cue.expressionId) { · <strong i18n="@@campaign.cues.expression">Expression</strong>: <span data-expression>{{ cue.expressionId }}</span> }
                }
                @default {
                  <strong i18n="@@campaign.cues.line">Spoken line</strong>: <span data-line>{{ cue.lineId ?? unnamed }}</span>
                  @if (cue.assetId) {
                    <button type="button" data-audio-toggle (click)="toggle(cue)">{{ state(cue) === 'playing' ? stopLabel : playLabel }}</button>
                    <span class="muted" role="status" data-audio-status>{{ state(cue) === 'unavailable' ? unavailable : '' }}</span>
                  } @else { <span class="muted" data-audio-none>{{ noAudio }}</span> }
                }
              }
            </li>
          }
        </ol>
      </section>
    }
  `,
})
export class CampaignCues {
  readonly cues = input.required<readonly PresentationCue[]>();
  private readonly factory = inject(CUE_AUDIO);
  private readonly players = new Map<string, CueAudio>();
  private readonly states = signal<Readonly<Record<string, AudioState>>>({});
  /** `allowed` only when the browser answered that the player did not ask for reduced motion. */
  readonly motion = signal<'allowed' | 'reduced'>('reduced');
  readonly unnamed = $localize`:@@campaign.cues.unnamed:(not named)`;
  readonly playLabel = $localize`:@@campaign.cues.play:Play line audio`;
  readonly stopLabel = $localize`:@@campaign.cues.stop:Stop line audio`;
  readonly unavailable = $localize`:@@campaign.cues.unavailable:Audio is not available for this line. The text above is unchanged.`;
  readonly noAudio = $localize`:@@campaign.cues.noAudio:No audio for this line.`;

  /** Follow the motion preference and stop every player when the view goes away. */
  constructor() {
    const destroy = inject(DestroyRef);
    const query = typeof globalThis.matchMedia === 'function' ? globalThis.matchMedia(REDUCED_MOTION) : null;
    if (query) {
      const apply = () => this.motion.set(query.matches ? 'reduced' : 'allowed');
      apply();
      query.addEventListener?.('change', apply);
      destroy.onDestroy(/** Stop following the preference. */ () => query.removeEventListener?.('change', apply));
    }
    destroy.onDestroy(/** Leaving the screen silences any line still playing. */ () => this.stopAll());
  }

  /** Playback state of a cue's audio; `idle` until the player asks. */
  state(cue: PresentationCue): AudioState { return this.states()[cue.deliveryId] ?? 'idle'; }

  /** Start the cue's line, or stop it when it is already playing. Only reachable through a button. */
  toggle(cue: PresentationCue): void {
    if (cue.assetId === null) return;
    if (this.state(cue) === 'playing') { this.stop(cue.deliveryId, 'idle'); return; }
    this.stopAll();
    const id = cue.deliveryId;
    try {
      const player = this.factory(cueAssetPath(cue.assetId));
      this.players.set(id, player);
      player.onended = () => this.finish(id, player, 'idle');
      player.onerror = () => this.finish(id, player, 'unavailable');
      this.mark(id, 'playing');
      void Promise.resolve(player.play()).catch(/** A refused or failed load leaves the text notice. */ () => this.finish(id, player, 'unavailable'));
    } catch {
      this.players.delete(id);
      this.mark(id, 'unavailable');
    }
  }

  /** Apply the end or failure of a player only while it is still the current one for the cue. */
  private finish(id: string, player: CueAudio, next: AudioState): void {
    if (this.players.get(id) === player) this.stop(id, next);
  }

  /** Detach and pause one player, then record the resulting state. */
  private stop(id: string, next: AudioState): void {
    const player = this.players.get(id);
    this.players.delete(id);
    if (player) {
      player.onended = null;
      player.onerror = null;
      try { player.pause(); } catch { /* A player that cannot pause is already stopped. */ }
    }
    this.mark(id, next);
  }

  private stopAll(): void { for (const id of [...this.players.keys()]) this.stop(id, 'idle'); }

  private mark(id: string, next: AudioState): void { this.states.update((all) => ({ ...all, [id]: next })); }
}
