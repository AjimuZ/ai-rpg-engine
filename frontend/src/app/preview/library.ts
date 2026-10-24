/** Library shell separates adventure previews from reusable installed pack management. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PreviewState } from './preview-state';

/** Render local sample adventures; all links navigate without creating saved state. */
@Component({
  selector: 'app-library', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page-heading"><h1>Your adventures</h1>
      <p>Independent worlds, each with its own playthroughs.</p>
      <p class="notice">Preview library. These samples are local to this interface; saved adventures are not connected yet.</p>
      <button disabled>New adventure</button>
    </section>
    <div class="library-grid">
      @for (adventure of state.adventures; track adventure.id) {
        <article class="adventure-card">
          <img [src]="adventure.scene" [alt]="adventure.sceneAlt" width="1200" height="640" />
          <div><h2>{{ adventure.title }}</h2><p>{{ adventure.description }}</p><p class="muted">No saved playthrough connected</p>
            <a class="button" [routerLink]="['/adventures', adventure.id, 'story']">Open story preview</a>
            <a [routerLink]="['/adventures', adventure.id, 'create']">New playthrough preview</a>
          </div>
        </article>
      }
    </div>
    <section class="panel"><h2>Installed packs</h2><p>Reusable content sources are managed separately from adventures.</p>
      <p>Pack management is not connected in this preview.</p><button disabled>Import pack</button>
    </section>
  `,
})
export class Library {
  readonly state = inject(PreviewState);
}
