/** Library shell separates adventure previews from reusable installed pack management. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PreviewState } from './preview-state';

/** Render local sample adventures; all links navigate without creating saved state. */
@Component({
  selector: 'app-library', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page-heading"><h1 i18n="@@library.title">Your adventures</h1>
      <p i18n="@@library.intro">Independent worlds, each with its own playthroughs.</p>
      <p class="notice" i18n="@@library.notice">Preview library. These samples are local to this interface; saved adventures are not connected yet.</p>
      <button disabled i18n="@@library.new">New adventure</button>
    </section>
    <div class="library-grid">
      @for (adventure of state.adventures; track adventure.id) {
        <article class="adventure-card">
          <img [src]="adventure.scene" [alt]="adventure.sceneAlt" width="1200" height="640" />
          <div><h2>{{ adventure.title }}</h2><p>{{ adventure.description }}</p><p class="muted" i18n="@@library.noSave">No saved playthrough connected</p>
            <a class="button" [routerLink]="['/adventures', adventure.id, 'story']" i18n="@@library.open">Open story preview</a>
            <a [routerLink]="['/adventures', adventure.id, 'create']" i18n="@@library.create">New playthrough preview</a>
          </div>
        </article>
      }
    </div>
    <section class="panel"><h2 i18n="@@library.packs">Installed packs</h2><p i18n="@@library.packIntro">Reusable content sources are managed separately from adventures.</p>
      <p i18n="@@library.packUnavailable">Pack management is not connected in this preview.</p><button disabled i18n="@@library.import">Import pack</button>
    </section>
  `,
})
export class Library {
  readonly state = inject(PreviewState);
}
