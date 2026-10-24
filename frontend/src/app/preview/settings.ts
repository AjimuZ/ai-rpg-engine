/** Local reading settings shell; runtime, audio and localization remain explicitly unavailable. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PreviewState } from './preview-state';
import { RouterLink } from '@angular/router';

/** Apply bounded in-memory reading preferences; accepts native select values and ignores invalid numbers. */
@Component({
  selector: 'app-settings', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel narrow"><h1>Settings</h1><h2>Reading</h2>
    <label for="text-size">Story text size</label>
    <select id="text-size" [value]="state.textSize()" (change)="changeSize($event)">
      @for (size of sizes; track size) { <option [value]="size">{{ size }} px</option> }
    </select>
    <label for="reading-layout">Story layout</label>
    <select id="reading-layout" [value]="state.readingLayout()" (change)="changeLayout($event)">
      <option value="visual-novel">Visual novel</option><option value="text-focused">Text focused (no artwork)</option>
    </select><p>Reading preferences last for this tab session. Reload restores defaults.</p>
    <h2>Language</h2><p>English preview. Italian interface resources and persisted language selection are planned.</p>
    <h2>Sound and appearance</h2><p>No audio is loaded. Motion is disabled in this preview.</p>
    <h2>Runtime</h2><p>No campaign or narration service is connected.</p>
    <h2>Credits</h2><p>Original scene, speaker and item vector illustrations, icons, frames and control geometry. Copyright 2026 Azim Ben Othman; distributed under the project MIT license. System fonts; no external media requests.</p>
    <p><a routerLink="/interface-kit">Review interface graphic kit</a> · <a href="/ui/README.md">Asset sources and provenance</a> · <a href="/ui/LICENSE.txt">MIT license</a></p>
    </section>`,
})
export class Settings {
  readonly state = inject(PreviewState);
  readonly sizes = Array.from({ length: 10 }, /** Enumerate the documented inclusive 15–24 px range. */ (_, index) => index + 15);

  /** Read a native select event; absent targets or invalid values leave settings unchanged. */
  changeSize(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.state.setTextSize(Number(event.target.value));
  }

  /** Apply a native layout selection; missing targets and unsupported values leave preferences unchanged. */
  changeLayout(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.state.setReadingLayout(event.target.value);
  }
}
