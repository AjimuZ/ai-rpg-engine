/**
 * Live story reading of one playthrough (P17.3-B): the visual-novel and text-focused layouts, the scene and speaker portrait with
 * their graphic states, the attributed current passage, a two-line read-back and the full chronicle.
 *
 * The component only presents entries the parent derived from published narration; it never fetches, submits or advances anything.
 * It reuses the reading tokens and layout choice of {@link PreviewState} and the style rules of the local preview story, so both
 * screens look and behave alike. In the text-focused layout no image element exists and no image is requested. A scene or portrait
 * that is missing or fails leaves a text label, so the passage, the read-back and the chronicle never depend on a picture.
 */
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { PreviewState } from '../preview/preview-state';
import { ArtworkIndex } from './campaign-artwork';
import { StoryEntry } from './campaign-story-model';

/** Graphic state of the scene, exposed as `data-scene` for styling and review; reading never waits for it. */
export type SceneState = 'hidden' | 'none' | 'loading' | 'ready' | 'failed';

/** Present the attributed entries of one playthrough in the chosen reading layout. */
@Component({
  selector: 'app-campaign-story', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (entries().length > 0) {
      <section class="story campaign-story" data-live-story [class.text-focused]="layout() === 'text-focused'" [attr.data-layout]="layout()"
        [attr.data-scene]="sceneState()" [style.--reading-size]="state.textSize() + 'px'" [style.--reading-leading]="state.leading()"
        [style.--reading-measure]="state.measureRem() + 'rem'">
        <div class="story-bar">
          @if (sceneLabel()) { <h3 class="story-title" data-scene-label>{{ sceneLabel() }}</h3> }
          <label for="live-layout" i18n="@@settings.layout">Story layout</label>
          <select id="live-layout" [value]="layout()" (change)="changeLayout($event)">
            <option value="visual-novel" i18n="@@settings.visualNovel">Visual novel</option>
            <option value="text-focused" i18n="@@settings.textFocused">Text focused (no artwork)</option>
          </select>
        </div>
        @if (layout() === 'visual-novel') {
          <figure class="scene">
            @if (sceneUrl() && !imageFailed()) {
              <img [src]="sceneUrl()" [alt]="sceneLabel()" width="1200" height="640" (load)="imageLoaded.set(true)" (error)="imageFailed.set(true)" />
            } @else { <p class="scene-fallback" data-scene-fallback i18n="@@campaign.story.sceneUnavailable">Scene illustration unavailable. {{ sceneLabel() }}</p> }
            <figcaption i18n="@@campaign.story.sceneCaption">Scene</figcaption>
          </figure>
        }
        @if (current(); as passage) {
          <section class="dialogue" data-live-passage tabindex="-1" aria-label="Current passage" i18n-aria-label="@@story.passageLabel" aria-live="polite" aria-atomic="true">
            @if (passage.kind === 'speech' && layout() === 'visual-novel') {
              <div class="portrait-frame">
                @if (portraitUrl() && !portraitFailed()) {
                  <img class="portrait" [src]="portraitUrl()" alt="" width="96" height="112" (error)="portraitFailed.set(true)" />
                } @else { <span class="portrait-fallback" aria-hidden="true">{{ speakerName(passage).slice(0, 1) }}</span> }
              </div>
            }
            <div class="dialogue-text"><p class="speaker">{{ speakerName(passage) }} <span class="muted">{{ roleLabel(passage) }}</span></p>
              <p class="passage" [attr.lang]="passage.locale">{{ passage.text }}</p></div>
          </section>
          <!-- Outside the live region on purpose: a new passage announces itself, not the read-back. -->
          @if (previous(); as before) {
            <p class="previous" data-previous><span class="muted" i18n="@@story.previously">Previously</span> · <strong>{{ speakerName(before) }}</strong> ({{ roleLabel(before) }}): <span [attr.lang]="before.locale">{{ before.text }}</span></p>
          }
        }
        <details class="chronicle" data-chronicle><summary><span i18n="@@story.chronicle">Chronicle</span></summary>
          <ol>
            @for (entry of entries(); track entry.id) {
              <li [attr.data-entry-id]="entry.id"><p [attr.lang]="entry.locale"><strong>{{ speakerName(entry) }} ({{ roleLabel(entry) }}):</strong> {{ entry.text }}</p></li>
            }
          </ol>
        </details>
      </section>
    }
  `,
})
export class CampaignStory {
  /** Attributed passages, oldest first; the newest is the current one. */
  readonly entries = input.required<readonly StoryEntry[]>();
  /** Hash URL of the scene in force, or null when the scene has no registered image. */
  readonly sceneUrl = input<string | null>(null);
  /** Public name of the place, used as title, image description and fallback text. */
  readonly sceneLabel = input<string>('');
  /** Artwork of the playthrough, used to find the speaker's portrait by the stable actor identity. */
  readonly artwork = input<ArtworkIndex | null>(null);
  readonly state = inject(PreviewState);
  readonly layout = this.state.readingLayout;
  private readonly narrator = $localize`:@@story.narrator:Narrator`;
  private readonly narration = $localize`:@@story.narration:Narration`;
  private readonly character = $localize`:@@story.character:Character`;
  readonly current = computed(() => this.entries().at(-1));
  readonly previous = computed(() => this.entries().at(-2));
  readonly portraitUrl = computed(() => {
    const actor = this.current()?.actorId;
    return actor ? this.artwork()?.imageUrl('portrait', actor) ?? null : null;
  });
  /** A failed scene must not hide the next place's image, so the failure follows the URL. */
  readonly imageFailed = linkedSignal({ source: this.sceneUrl, computation: /** A new image starts unfailed. */ () => false });
  readonly imageLoaded = linkedSignal({ source: this.sceneUrl, computation: /** A new image starts unloaded. */ () => false });
  /** A failed portrait must not suppress the next speaker's artwork. */
  readonly portraitFailed = linkedSignal({ source: this.portraitUrl, computation: /** A new portrait starts unfailed. */ () => false });
  /** `hidden` in the text layout (no image is requested), `none` without registered art, otherwise `loading`, `ready` or `failed`. */
  readonly sceneState = computed((): SceneState => {
    if (this.layout() === 'text-focused') return 'hidden';
    if (!this.sceneUrl()) return 'none';
    return this.imageFailed() ? 'failed' : this.imageLoaded() ? 'ready' : 'loading';
  });

  /** The name that accompanies a passage; a narrator has no name of its own. */
  speakerName(entry: StoryEntry): string { return entry.kind === 'speech' ? entry.name ?? this.character : this.narrator; }

  /** The explicit role label, so attribution never relies on colour. */
  roleLabel(entry: StoryEntry): string { return entry.kind === 'speech' ? this.character : this.narration; }

  /** Apply the layout the player chose; the choice persists with the other reading preferences of the tab. */
  changeLayout(event: Event): void { this.state.setReadingLayout((event.target as HTMLSelectElement).value); }
}
