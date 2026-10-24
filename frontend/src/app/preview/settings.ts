/** Local reading settings shell; runtime, audio and localization remain explicitly unavailable. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PreviewState } from './preview-state';

/** Apply bounded in-memory reading preferences; accepts native select values and ignores invalid numbers. */
@Component({
  selector: 'app-settings', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel narrow"><h1>Settings</h1><h2>Reading</h2>
    <label for="text-size">Story text size</label>
    <select id="text-size" [value]="state.textSize()" (change)="changeSize($event)">
      @for (size of sizes; track size) { <option [value]="size">{{ size }} px</option> }
    </select><p>Reading preferences last for this tab session. Reload restores defaults.</p>
    <h2>Language</h2><p>English preview. Italian interface resources and persisted language selection are planned.</p>
    <h2>Sound and appearance</h2><p>No audio is loaded. Motion is disabled in this preview.</p>
    <h2>Runtime</h2><p>No campaign or narration service is connected.</p>
    <h2>Credits</h2><p>Original woodland and station vector illustrations, distributed under the project MIT license. System fonts; no external media requests.</p>
    </section>`,
})
export class Settings {
  readonly state = inject(PreviewState);
  readonly sizes = Array.from({ length: 10 }, /** Enumerate the documented inclusive 15–24 px range. */ (_, index) => index + 15);

  /** Read a native select event; absent targets or invalid values leave settings unchanged. */
  changeSize(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.state.setTextSize(Number(event.target.value));
  }
}
