/** Scoped preview context and navigation; unknown IDs cannot fall through to another adventure. */
import { ChangeDetectionStrategy, Component, computed, inject, Injectable } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PreviewState } from './preview-state';

/** Resolve parent route parameters reactively for child screens; unknown IDs produce undefined. */
@Injectable()
export class WorkspaceContext {
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  private readonly state = inject(PreviewState);
  readonly adventure = computed(/** Route reuse must resolve the new identity rather than retain old content. */ () => this.state.find(this.params().get('adventureId') ?? ''));
}

/** Render compact navigation around one known preview; invalid IDs show an actionable fallback. */
@Component({
  selector: 'app-workspace',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  providers: [WorkspaceContext],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (context.adventure(); as adventure) {
      <header class="workspace-heading"><p>{{ adventure.title }} <span class="badge">Preview</span></p>
        <nav aria-label="Adventure">
          @for (page of pages; track page.path) {
            <a [routerLink]="page.path" routerLinkActive="selected" ariaCurrentWhenActive="page">{{ page.label }}</a>
          }
        </nav>
      </header>
      <router-outlet />
    } @else {
      <section class="panel"><h1>Adventure unavailable</h1><p>This preview identity is unknown.</p><a routerLink="/library">Return to library</a></section>
    }
  `,
})
export class Workspace {
  readonly context = inject(WorkspaceContext);
  readonly pages = [
    { path: 'story', label: 'Story' }, { path: 'sheet', label: 'Character' },
    { path: 'inventory', label: 'Inventory' }, { path: 'journal', label: 'Journal' },
    { path: 'create', label: 'New playthrough' },
  ];
}
