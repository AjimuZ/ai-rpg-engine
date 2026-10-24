/** Local reading and language controls; compiled variants reload only after preview persistence checks pass. */
import { afterNextRender, ChangeDetectionStrategy, Component, ElementRef, inject } from '@angular/core';
import { PreviewState } from './preview-state';
import { RouterLink } from '@angular/router';
import { UiLanguage } from './ui-language';

/** Apply validated reading/language preferences; invalid events are ignored and persistence failures remain visible. */
@Component({
  selector: 'app-settings', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel narrow"><h1 i18n="@@settings.title">Settings</h1><h2 i18n="@@settings.reading">Reading</h2>
    <label for="text-size" i18n="@@settings.textSize">Story text size</label>
    <select id="text-size" (change)="changeSize($event)">
      <!-- Options are created by a loop; select the restored value on each option after it exists. -->
      @for (size of sizes; track size) { <option [value]="size" [selected]="size === state.textSize()" i18n="@@settings.pixels">{{ size }} px</option> }
    </select>
    <label for="reading-layout" i18n="@@settings.layout">Story layout</label>
    <select id="reading-layout" [value]="state.readingLayout()" (change)="changeLayout($event)">
      <option value="visual-novel" i18n="@@settings.visualNovel">Visual novel</option><option value="text-focused" i18n="@@settings.textFocused">Text focused (no artwork)</option>
    </select><p i18n="@@settings.tabPreferences">Reading preferences and passage positions survive reload in this tab.</p>
    @if (state.persistence() !== 'saved') { <p role="status" i18n="@@settings.readingStorage">Reading preferences could not be restored or saved. Language reload requires available tab storage.</p> }
    <h2 i18n="@@settings.language">Language</h2>
    @if (language.available) {
      <label for="ui-language" i18n="@@settings.interfaceLanguage">Interface language</label>
      <select id="ui-language" [value]="language.current" (change)="changeLanguage($event)" aria-describedby="language-help language-status">
        <option value="en" lang="en">English</option><option value="it" lang="it">Italiano</option>
      </select>
      <p id="language-help" i18n="@@settings.languageHelp">Changing language saves your preference on this device and reloads the interface. Preview drafts, selections and reading positions are kept. No action is sent.</p>
    } @else { <p i18n="@@settings.languagePending">Language selection is available in the English and Italian review variants. Packaged language delivery is still pending.</p> }
    <p id="language-status" role="status">{{ languageMessages[language.notice()] }}</p>
    <h2 i18n="@@settings.sound">Sound and appearance</h2><p i18n="@@settings.noAudio">No audio is loaded. Motion is disabled in this preview.</p>
    <h2 i18n="@@settings.runtime">Runtime</h2><p i18n="@@settings.noRuntime">No campaign or narration service is connected.</p>
    <h2 i18n="@@settings.credits">Credits</h2><p i18n="@@settings.rights">Original scene, speaker and item vector illustrations, icons, frames and control geometry. Copyright 2026 Azim Ben Othman; distributed under the project MIT license. System fonts; no external media requests.</p>
    <p><a routerLink="/interface-kit" i18n="@@settings.kit">Review interface graphic kit</a> · <a href="/ui/README.md" i18n="@@settings.sources">Asset sources and provenance</a> · <a href="/ui/LICENSE.txt" i18n="@@settings.license">MIT license</a></p>
    </section>`,
})
export class Settings {
  readonly state = inject(PreviewState);
  readonly language = inject(UiLanguage);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly languageMessages = {
    none: '',
    unsupported: $localize`:@@settings.unsupportedLanguage:Saved language is unsupported. English is the default; choose English or Italian.`,
    storage: $localize`:@@settings.languageStorage:Language could not be saved or reloaded. Keep this tab open to preserve your work and allow device and tab storage before trying again.`,
    unsafe: $localize`:@@settings.languageUnsafe:Language reload is paused because drafts are not safely saved or recovery is unresolved. Keep this tab open and resolve recovery before trying again.`,
  };
  readonly sizes = Array.from({ length: 10 }, /** Enumerate the documented inclusive 15–24 px range. */ (_, index) => index + 15);

  /** Restore only an explicit language reload's equivalent control after rendering; ordinary navigation retains router focus. */
  constructor() {
    afterNextRender(/** A one-use marker prevents later Settings visits from stealing keyboard focus. */ () => {
      if (this.language.restoreFocus()) this.element.nativeElement.querySelector<HTMLElement>('#ui-language')?.focus();
    });
  }

  /** Apply native language selection; rejected reloads reset the control to the currently rendered locale. */
  changeLanguage(event: Event): void {
    if (!(event.target instanceof HTMLSelectElement)) return;
    this.language.change(event.target.value);
    event.target.value = this.language.current;
  }

  /** Read a native select event; absent targets or invalid values leave settings unchanged. */
  changeSize(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.state.setTextSize(Number(event.target.value));
  }

  /** Apply a native layout selection; missing targets and unsupported values leave preferences unchanged. */
  changeLayout(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.state.setReadingLayout(event.target.value);
  }
}
