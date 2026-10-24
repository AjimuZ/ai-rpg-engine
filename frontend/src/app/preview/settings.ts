/** Local reading settings with extracted shell text; runtime, audio and language switching remain unavailable. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PreviewState } from './preview-state';
import { RouterLink } from '@angular/router';

/** Apply bounded in-memory reading preferences; accepts native select values and ignores invalid numbers. */
@Component({
  selector: 'app-settings', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel narrow"><h1 i18n="@@settings.title">Settings</h1><h2 i18n="@@settings.reading">Reading</h2>
    <label for="text-size" i18n="@@settings.textSize">Story text size</label>
    <select id="text-size" [value]="state.textSize()" (change)="changeSize($event)">
      @for (size of sizes; track size) { <option [value]="size" i18n="@@settings.pixels">{{ size }} px</option> }
    </select>
    <label for="reading-layout" i18n="@@settings.layout">Story layout</label>
    <select id="reading-layout" [value]="state.readingLayout()" (change)="changeLayout($event)">
      <option value="visual-novel" i18n="@@settings.visualNovel">Visual novel</option><option value="text-focused" i18n="@@settings.textFocused">Text focused (no artwork)</option>
    </select><p i18n="@@settings.tabPreferences">Reading preferences last for this tab session. Reload restores defaults.</p>
    <h2 i18n="@@settings.language">Language</h2><p i18n="@@settings.languagePending">Language selection will be available when all interface screens support English and Italian.</p>
    <h2 i18n="@@settings.sound">Sound and appearance</h2><p i18n="@@settings.noAudio">No audio is loaded. Motion is disabled in this preview.</p>
    <h2 i18n="@@settings.runtime">Runtime</h2><p i18n="@@settings.noRuntime">No campaign or narration service is connected.</p>
    <h2 i18n="@@settings.credits">Credits</h2><p i18n="@@settings.rights">Original scene, speaker and item vector illustrations, icons, frames and control geometry. Copyright 2026 Azim Ben Othman; distributed under the project MIT license. System fonts; no external media requests.</p>
    <p><a routerLink="/interface-kit" i18n="@@settings.kit">Review interface graphic kit</a> · <a href="/ui/README.md" i18n="@@settings.sources">Asset sources and provenance</a> · <a href="/ui/LICENSE.txt" i18n="@@settings.license">MIT license</a></p>
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
