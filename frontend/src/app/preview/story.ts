/** Render local public passages in scene-led or artwork-free layouts; no world actions or transport occur. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
import { Passage } from './preview-state';
import { PreviewState } from './preview-state';
import { WorkspaceContext } from './workspace';

/** Accept scoped preview content and reading preferences, rendering live attribution; missing content/media stays readable without throwing. */
@Component({
  selector: 'app-story', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (context.adventure(); as adventure) {
      <section class="story" [class.text-focused]="state.readingLayout() === 'text-focused'" [style.--reading-size]="state.textSize() + 'px'">
        <h1 class="story-title">{{ adventure.location }}</h1>
        @if (state.readingLayout() === 'visual-novel') {
        <figure class="scene">
          @if (!imageFailed()) { <img [src]="adventure.scene" [alt]="adventure.sceneAlt" (error)="imageFailed.set(true)" width="1200" height="640" /> }
          @else { <p class="scene-fallback">Scene illustration unavailable. {{ adventure.sceneAlt }}</p> }
          <figcaption>Illustrative scene · local preview</figcaption>
        </figure>
        } @else { <p class="scene-description">{{ adventure.sceneAlt }}</p> }
        @if (passage(); as current) {
          <section #dialogue class="dialogue" tabindex="-1" aria-label="Current passage" aria-live="polite" aria-atomic="true">
            @if (current.speaker === 'character' && state.readingLayout() === 'visual-novel') {
              <div class="portrait-frame">
                @if (speaker()?.portrait && !portraitFailed()) {
                  <img class="portrait" [src]="speaker()!.portrait" alt="" width="96" height="112" (error)="portraitFailed.set(true)" />
                } @else { <span class="portrait-fallback" aria-hidden="true">{{ speakerName(current).slice(0, 1) }}</span> }
              </div>
            }
            <div class="dialogue-text"><p class="speaker">{{ speakerName(current) }}
              <span class="muted">{{ current.speaker === 'character' ? 'Character' : current.speaker === 'player' ? 'Player' : 'Narration' }}</span>
            </p>
            <p class="passage">{{ current.text }}</p></div>
          </section>
          <div class="reading-controls"><button (click)="advance(dialogue)" [disabled]="atEnd()" aria-describedby="reading-progress">Continue</button>
            <span id="reading-progress">Passage {{ state.position(adventure.id) + 1 }} of {{ adventure.passages.length }} · {{ atEnd() ? 'End of available passages' : 'reading only' }}</span>
          </div>
        } @else {
          <p role="status">No passages are available.</p>
        }
        <details class="chronicle"><summary>Chronicle</summary>
          <ol>
          @for (entry of adventure.passages.slice(0, state.position(adventure.id) + 1); track $index) {
            <li><p><strong>{{ speakerName(entry) }} ({{ entry.speaker === 'character' ? 'Character' : entry.speaker === 'player' ? 'Player' : 'Narration' }}):</strong> {{ entry.text }}</p></li>
          }
          </ol>
        </details>
        <div class="composer"><label for="player-input">What do you say or do?</label>
          <textarea id="player-input" disabled placeholder="Player input will be available when a campaign is connected." rows="2"></textarea>
          <button disabled>Send</button><p class="muted">Reading never submits an action. No campaign is connected.</p>
        </div>
      </section>
    }
  `,
})
export class Story {
  readonly context = inject(WorkspaceContext);
  readonly state = inject(PreviewState);
  readonly imageFailed = linkedSignal({
    source: this.context.adventure,
    computation: /** A failed previous scene must not hide another adventure's illustration. */ () => false,
  });
  readonly passage = computed(/** Derive a supplied passage; missing identity safely renders no dialogue. */ () => {
    const adventure = this.context.adventure();
    return adventure?.passages[this.state.position(adventure.id)];
  });
  readonly speaker = computed(/** Resolve only a public identity scoped to the selected adventure; unknown IDs have no portrait. */ () => {
    const current = this.passage();
    return current?.speakerId ? this.context.adventure()?.speakers[current.speakerId] : undefined;
  });
  readonly portraitFailed = linkedSignal({
    source: this.speaker,
    computation: /** An asset failure must not suppress a later speaker's artwork. */ () => false,
  });
  readonly atEnd = computed(/** No identity or supplied passage means there is nothing to advance. */ () => {
    const adventure = this.context.adventure();
    return !adventure || this.state.position(adventure.id) >= adventure.passages.length - 1;
  });

  /** Resolve an attributed passage label; unknown public people use their supplied label or neutral wording. */
  speakerName(entry: Passage): string {
    if (entry.speaker === 'player') return 'You';
    if (entry.speaker === 'narrator') return 'Narrator';
    const known = entry.speakerId ? this.context.adventure()?.speakers[entry.speakerId] : undefined;
    return known?.name.trim() || entry.name.trim() || 'Unknown character';
  }

  /** Reveal supplied text only; at the end, move focus before Continue disables to avoid losing keyboard position. */
  advance(dialogue: HTMLElement): void {
    const adventure = this.context.adventure();
    if (!adventure || this.atEnd()) return;
    this.state.advance(adventure.id);
    // The dialogue node survives passage changes; its polite live region announces the updated text.
    if (this.atEnd()) dialogue.focus();
  }
}
