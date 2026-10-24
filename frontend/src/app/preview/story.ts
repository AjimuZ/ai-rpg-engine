/** Visual-novel shell keeps sample reading separate from unimplemented world actions. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
import { PreviewState } from './preview-state';
import { WorkspaceContext } from './workspace';

/** Render supplied public passages with live attribution; Continue only advances local reading. */
@Component({
  selector: 'app-story', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (context.adventure(); as adventure) {
      <section class="story" [style.--reading-size]="state.textSize() + 'px'">
        <h1 class="story-title">{{ adventure.location }}</h1>
        <figure class="scene">
          @if (!imageFailed()) { <img [src]="adventure.scene" [alt]="adventure.sceneAlt" (error)="imageFailed.set(true)" width="1200" height="640" /> }
          @else { <p class="scene-fallback">Scene illustration unavailable. {{ adventure.sceneAlt }}</p> }
          <figcaption>Illustrative scene · local preview</figcaption>
        </figure>
        @if (passage(); as current) {
          <section class="dialogue" aria-label="Current passage" aria-live="polite" aria-atomic="true">
            <p class="speaker">{{ current.speaker === 'player' ? 'You' : current.name }}
              <span class="muted">{{ current.speaker === 'character' ? 'Character' : current.speaker === 'player' ? 'Player' : 'Narration' }}</span>
            </p>
            <p class="passage">{{ current.text }}</p>
          </section>
        }
        <div class="reading-controls"><button (click)="state.advance(adventure.id)" [disabled]="state.position(adventure.id) >= adventure.passages.length - 1">Continue</button>
          <span>Passage {{ state.position(adventure.id) + 1 }} of {{ adventure.passages.length }} · reading only</span>
        </div>
        <details class="chronicle"><summary>Chronicle</summary>
          @for (entry of adventure.passages.slice(0, state.position(adventure.id) + 1); track $index) {
            <p><strong>{{ entry.speaker === 'player' ? 'You' : entry.name }}:</strong> {{ entry.text }}</p>
          }
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
}
