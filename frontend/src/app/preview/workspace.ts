/** Scoped preview context and navigation; unknown IDs cannot fall through to another adventure. */
import { ChangeDetectionStrategy, Component, computed, inject, Injectable } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PreviewState } from './preview-state';
import { contentTerm, ContentTerm, displayLabel } from './content-metadata';
import { UiIcon } from './ui-kit';

/** Resolve parent route parameters reactively for child screens; unknown IDs produce undefined. */
@Injectable()
export class WorkspaceContext {
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  private readonly state = inject(PreviewState);
  readonly adventure = computed(/** Route reuse must resolve the new identity rather than retain old content. */ () => this.state.find(this.params().get('adventureId') ?? ''));

  /** Return scoped terminology for a known key; absent content safely uses the neutral label. */
  term(key: ContentTerm): string {
    return contentTerm(this.adventure()?.content, key);
  }
}

/** Render compact navigation around one known preview; invalid IDs show an actionable fallback. */
@Component({
  selector: 'app-workspace',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, UiIcon],
  providers: [WorkspaceContext],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (context.adventure(); as adventure) {
      <header class="workspace-heading"><p>{{ adventure.title }} <span class="badge" i18n="@@workspace.preview">Preview</span></p>
        <nav aria-label="Adventure" i18n-aria-label="@@workspace.navigation">
          @for (page of pages(); track page.path) {
            <a [routerLink]="page.path" routerLinkActive="selected" ariaCurrentWhenActive="page"><app-ui-icon [name]="page.path" />{{ page.label }}</a>
          }
        </nav>
      </header>
      <router-outlet />
    } @else {
      <section class="panel"><h1 i18n="@@workspace.missing">Adventure unavailable</h1><p i18n="@@workspace.unknown">This preview identity is unknown.</p><a routerLink="/library" i18n="@@workspace.return">Return to library</a></section>
    }
  `,
})
export class Workspace {
  readonly context = inject(WorkspaceContext);
  readonly pages = computed(/** Derive supported links; content controls labels and optional panel presence only. */ () => {
    const modules = this.context.adventure()?.content.modules;
    return [
      ...(['story', 'sheet', 'inventory', 'journal'] as const).map(
        /** Labels never change the stable route identity. */ (path) => ({ path, label: this.context.term(path) })),
      ...(modules?.guide ? [{ path: 'guide', label: displayLabel(modules.guide.title, $localize`:@@terms.guide:Guide`) }] : []),
      ...(modules?.creativeAbilities ? [{ path: 'creative', label: displayLabel(modules.creativeAbilities.title, $localize`:@@terms.creative:Creative abilities`) }] : []),
      { path: 'create', label: $localize`:@@workspace.create:New playthrough` },
    ];
  });
}
