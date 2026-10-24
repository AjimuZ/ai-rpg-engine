/** Render local public passages in scene-led or artwork-free layouts; no world actions or transport occur. */
import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, linkedSignal, untracked } from '@angular/core';
import { QuickReply, StoryInputState, StoryStatus } from './story-input-state';
import { Passage } from './preview-state';
import { PreviewState } from './preview-state';
import { WorkspaceContext } from './workspace';

/** Accept scoped preview content and reading preferences, rendering live attribution; missing content/media stays readable without throwing. */
@Component({
  selector: 'app-story',
  host: { '(window:offline)': 'connectionChanged(false)', '(window:online)': 'connectionChanged(true)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
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
        <div class="composer">
          <p role="status" aria-live="polite">{{ statusMessage() }}</p>
          <p id="suggestion-help" class="muted">Suggestions fill an empty draft. Edit freely; clear your draft before choosing another.</p>
          <ul class="quick-replies" aria-label="Suggestions">
            @for (reply of input.suggestions(); track reply.id) {
              <li><button type="button" data-suggestion [disabled]="!canChoose()" aria-describedby="suggestion-help"
                (click)="choose(reply, playerInput)">{{ reply.kind === 'question' ? 'Question' : reply.kind === 'speech' ? 'Speech' : 'Action' }}: {{ reply.text }}</button>
                <button type="button" data-dismiss [attr.aria-label]="'Dismiss suggestion: ' + reply.text"
                  (click)="dismiss(reply.id, playerInput)">Dismiss</button></li>
            }
          </ul>
          <label for="player-input">What do you say or do?</label>
          <textarea #playerInput id="player-input" [value]="input.draft().text" maxlength="4000"
            placeholder="Write a draft…" rows="3" aria-describedby="draft-status composer-help"
            (input)="saveDraft(playerInput)" (select)="saveDraft(playerInput)" (keyup)="saveDraft(playerInput)"
            (click)="saveDraft(playerInput)" (focus)="restoreSelection(playerInput)"></textarea>
          <div class="composer-actions"><button type="button" data-send disabled>Send</button>
            <button type="button" (click)="clearDraft(playerInput)" [disabled]="input.draft().text === ''">Clear draft</button></div>
          <p id="draft-status" class="muted">{{ input.persistence() === 'saved' ? 'Draft saved on this device.' : input.persistence() === 'invalid' ? 'Saved draft could not be restored. New edits will replace it.' : 'Draft kept in this tab only. Device storage is unavailable.' }}</p>
          <p id="composer-help" class="muted">Reading and suggestions never submit an action. No campaign is connected; Send is unavailable.</p>
        </div>
      </section>
    }
  `,
})
export class Story {
  readonly context = inject(WorkspaceContext);
  readonly state = inject(PreviewState);
  readonly input = inject(StoryInputState);
  private epoch = 0;
  readonly canChoose = computed(/** Draft/recovery guards mirror service checks so disabled chips explain availability. */ () =>
    this.input.draft().text === '' && ['preview', 'ready'].includes(this.input.status()));
  readonly statusMessage = computed(/** Use safe local wording; raw transport errors never reach live regions. */ () => {
    const messages: Record<StoryStatus, string> = {
      loading: 'Loading current context. You can keep drafting.',
      preview: 'Local preview. Drafting is available; no campaign is connected.',
      ready: 'Current context is available. No campaign transport is connected.',
      disconnected: 'Connection unavailable. Your last context and draft remain available.',
      reconnecting: 'Connection restored. Operation status must be checked before any retry. Your draft will not be sent.',
      error: 'Context could not be refreshed. Your last context and draft remain available.',
      'stale-revision': 'The world has changed. Refresh current context before sending; your draft is preserved.',
      clarification: 'Clarification is needed. Review the pending operation before sending another action.',
      cancelled: 'The operation was cancelled before commit. Your draft is preserved.',
      'narration-pending': 'The action was saved; narration is pending. Do not resend the action.',
      'restore-required': 'Campaign recovery is required before sending. Your draft is preserved.',
    };
    return messages[this.input.status()];
  });

  /** Bind public fixture context to one scope; reading changes presentation generation, never canonical revision. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Leaving the story invalidates callbacks even when returning to the same adventure later. */ () => this.input.deactivate(this.epoch));
    effect(/** Reused routes must replace context while preserving independently stored draft text. */ () => {
      const adventure = this.context.adventure();
      if (!adventure) return;
      const position = this.state.position(adventure.id);
      untracked(/** Projection writes must not subscribe this effect to draft or connection updates. */ () => {
        this.epoch = this.input.activate({ adventureId: adventure.id, campaignId: 'local-preview' });
        this.input.accept({ adventureId: adventure.id, campaignId: 'local-preview', revision: 0,
          presentationRevision: position, suggestions: adventure.passages[position]?.suggestions ?? [] }, this.epoch);
        if (this.input.status() === 'ready' || this.input.status() === 'loading') {
          this.input.setStatus(globalThis.navigator?.onLine === false ? 'disconnected' : 'preview', this.epoch);
        }
      });
    });
  }

  /** Save literal draft text and native selection; storage failures remain visible without blocking editing. */
  saveDraft(field: HTMLTextAreaElement): void { this.input.edit(field.value, field.selectionStart, field.selectionEnd); }

  /** Restore saved selection on keyboard focus without moving focus automatically on reload/navigation. */
  restoreSelection(field: HTMLTextAreaElement): void { field.setSelectionRange(this.input.draft().start, this.input.draft().end); }

  /** Populate an empty draft from current context and focus it for editing; rejection leaves the field untouched. */
  choose(reply: QuickReply, field: HTMLTextAreaElement): void {
    if (!this.input.choose(reply)) return;
    field.value = this.input.draft().text;
    field.focus();
    this.restoreSelection(field);
  }

  /** Dismiss a chip and preserve keyboard position before its focused button disappears; draft text is unchanged. */
  dismiss(id: string, field: HTMLTextAreaElement): void {
    field.focus();
    this.restoreSelection(field);
    this.input.dismiss(id);
  }

  /** Explicitly clear only this scope's unsent draft; no submission or reading advancement occurs. */
  clearDraft(field: HTMLTextAreaElement): void {
    this.input.edit('');
    field.value = '';
    field.focus();
  }

  /** Report browser connectivity as a hint only; returning online never asserts backend readiness or replays input. */
  connectionChanged(online: boolean): void {
    this.input.setStatus(online ? 'reconnecting' : 'disconnected', this.epoch);
  }

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
