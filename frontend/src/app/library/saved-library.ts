/**
 * Persisted library screens: the saved-adventure list embedded in the library page and the
 * scoped saved-adventure view with its playthrough selection (docs/25 section 3).
 *
 * Identity is always the server UUID. Equal titles stay separate cards, the selected adventure and
 * playthrough come only from the route, and switching cancels the previous reads so a late reply
 * for one adventure can never populate another adventure's header or list.
 *
 * Adventure titles and genres are free-form author metadata, never translated: they are shown
 * literally and carry the adventure's validated `sourceLocale` as their `lang`, so a screen reader
 * pronounces an English title correctly inside the Italian interface and vice versa (docs/29).
 */
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, resource, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CampaignPlaythrough } from '../campaign/campaign-view';
import {
  isUuid, SavedAdventure, SavedLibraryClient, SavedLibraryError, SavedLibraryFailure, SavedPlaythrough,
} from './saved-library-client';

/** Return the localized explanation for a failure; unknown errors use the generic outage text. */
export function failureMessage(error: unknown): string {
  const kind: SavedLibraryFailure = error instanceof SavedLibraryError ? error.kind : 'unavailable';
  switch (kind) {
    case 'missing': return $localize`:@@saved.missing:This saved adventure is unavailable.`;
    case 'unconfigured': return $localize`:@@saved.unconfigured:The local owner is not configured, so saved adventures cannot be read.`;
    case 'denied': return $localize`:@@saved.denied:This browser session was not admitted. Reload the page to try again.`;
    case 'invalid': return $localize`:@@saved.invalid:The saved library sent data this interface cannot verify, so nothing was shown.`;
    default: return $localize`:@@saved.unavailable:Saved adventures are unavailable right now.`;
  }
}

/** Return a short, stable identity tag so equal titles remain distinguishable to the reader. */
export function identityTag(id: string): string {
  return id.slice(0, 8);
}

/** List saved adventures page by page; leaving the library cancels any outstanding read. */
@Component({
  selector: 'app-saved-library', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="panel saved-library" aria-labelledby="saved-library-title"><h2 id="saved-library-title" i18n="@@saved.title">Saved adventures</h2>
      @switch (status()) {
        @case ('loading') { <p role="status" i18n="@@saved.loading">Loading saved adventures…</p> }
        @case ('failed') { <p class="notice" role="alert">{{ failure() }}</p> }
        @default {
          @if (adventures().length === 0) { <p i18n="@@saved.empty">No saved adventures yet.</p> }
          <ul class="saved-list">
            @for (adventure of adventures(); track adventure.adventureId) {
              <li class="saved-card" [attr.data-adventure-id]="adventure.adventureId">
                <h3 [attr.lang]="adventure.sourceLocale">{{ adventure.title }}</h3>
                <p><span class="badge" [attr.lang]="adventure.sourceLocale">{{ adventure.genre }}</span> <span class="muted" i18n="@@saved.reference">Reference {{ tag(adventure.adventureId) }}</span></p>
                <a [routerLink]="['/saved', adventure.adventureId]" i18n="@@saved.open">Open saved adventure</a>
              </li>
            }
          </ul>
          @if (nextAfter() && !loadingMore()) { <button type="button" (click)="more()" i18n="@@saved.more">Show more saved adventures</button> }
          @if (loadingMore()) { <p role="status" i18n="@@saved.loadingMore">Loading more…</p> }
          @if (moreFailure()) { <p class="notice" role="alert">{{ moreFailure() }}</p> }
        }
      }
    </section>
  `,
})
export class SavedLibrary {
  private readonly client = inject(SavedLibraryClient);
  // Aborted when the library is destroyed so a reply cannot update a detached screen.
  private readonly lifetime = new AbortController();
  readonly status = signal<'loading' | 'ready' | 'failed'>('loading');
  readonly failure = signal('');
  readonly adventures = signal<readonly SavedAdventure[]>([]);
  readonly nextAfter = signal<string | null>(null);
  readonly loadingMore = signal(false);
  readonly moreFailure = signal('');
  readonly tag = identityTag;

  /** Start the first page read and tie its cancellation to the component lifetime. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Detach pending reads when navigating away. */ () => this.lifetime.abort());
    this.client.adventures(null, this.lifetime.signal).then(
      /** Publish the validated page; aborted reads never reach this branch. */ (page) => {
        this.adventures.set(page.adventures);
        this.nextAfter.set(page.nextAfter);
        this.status.set('ready');
      },
      /** Show a localized failure unless the screen is already gone. */ (error: unknown) => {
        if (this.lifetime.signal.aborted) return;
        this.failure.set(failureMessage(error));
        this.status.set('failed');
      });
  }

  /** Append the next page; identities already shown are skipped so cards stay uniquely keyed. */
  more(): void {
    const after = this.nextAfter();
    if (!after || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.moreFailure.set('');
    this.client.adventures(after, this.lifetime.signal).then(
      /** Merge by identity, never by title. */ (page) => {
        const known = new Set(this.adventures().map(/** Existing identities. */ (item) => item.adventureId));
        this.adventures.update(/** Keep server order. */ (list) => [...list, ...page.adventures.filter(/** Drop repeats. */ (item) => !known.has(item.adventureId))]);
        this.nextAfter.set(page.nextAfter);
        this.loadingMore.set(false);
      },
      /** Keep the loaded cards and explain the failed continuation. */ (error: unknown) => {
        if (this.lifetime.signal.aborted) return;
        this.moreFailure.set(failureMessage(error));
        this.loadingMore.set(false);
      });
  }
}

/** Adventure and playthrough metadata loaded together for exactly one adventure identity. */
interface SavedScope {
  readonly adventure: SavedAdventure;
  readonly playthroughs: readonly SavedPlaythrough[];
}

/**
 * Show one saved adventure and its playthroughs; the header names the selected scope.
 *
 * The adventure comes from the `:adventureId` route parameter and the playthrough from the
 * optional `playthrough` query parameter. Neither is written to the server.
 */
@Component({
  selector: 'app-saved-adventure', imports: [RouterLink, CampaignPlaythrough], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (scope(); as current) {
      <header class="workspace-heading saved-heading" [attr.data-adventure-id]="current.adventure.adventureId">
        <p><strong [attr.lang]="current.adventure.sourceLocale">{{ current.adventure.title }}</strong>
          <span class="badge" [attr.lang]="current.adventure.sourceLocale">{{ current.adventure.genre }}</span>
          <span class="muted" i18n="@@saved.reference">Reference {{ tag(current.adventure.adventureId) }}</span></p>
        @if (selected(); as playthrough) {
          <p class="selected-playthrough" [attr.data-campaign-id]="playthrough.campaignId" i18n="@@saved.selected">Playthrough: {{ playthrough.title }}</p>
        } @else {
          <p class="selected-playthrough" i18n="@@saved.noneSelected">No playthrough selected</p>
        }
      </header>
      @if (unknownPlaythrough()) { <p class="notice" role="alert" i18n="@@saved.unknownPlaythrough">That playthrough does not belong to this adventure.</p> }
      <section class="panel"><h1 i18n="@@saved.playthroughs">Playthroughs</h1>
        <p class="notice" i18n="@@saved.readOnly">Select a playthrough to read its current state and action results. Sending actions in the browser is not connected yet.</p>
        @if (current.playthroughs.length === 0) { <p i18n="@@saved.noPlaythroughs">This adventure has no playthroughs yet.</p> }
        <ul class="saved-list">
          @for (playthrough of current.playthroughs; track playthrough.campaignId) {
            <li [attr.data-campaign-id]="playthrough.campaignId">
              <a [routerLink]="[]" [queryParams]="{ playthrough: playthrough.campaignId }" [attr.aria-current]="playthrough.campaignId === selected()?.campaignId ? 'true' : null">{{ playthrough.title }}</a>
              <span class="muted" i18n="@@saved.turn">Turn {{ playthrough.gameTurn }}</span>
              @if (playthrough.pendingDecisionAvailable) { <span class="badge" i18n="@@saved.pending">Decision pending</span> }
            </li>
          }
        </ul>
        <a routerLink="/library" i18n="@@workspace.return">Return to library</a>
      </section>
      @if (selected(); as playthrough) {
        <app-campaign-playthrough [adventureId]="current.adventure.adventureId" [campaignId]="playthrough.campaignId" />
      }
    } @else if (loaded.isLoading()) {
      <section class="panel"><p role="status" i18n="@@saved.loadingOne">Loading saved adventure…</p></section>
    } @else {
      <section class="panel"><h1 i18n="@@workspace.missing">Adventure unavailable</h1><p role="alert">{{ failure() }}</p><a routerLink="/library" i18n="@@workspace.return">Return to library</a></section>
    }
  `,
})
export class SavedAdventureView {
  private readonly route = inject(ActivatedRoute);
  private readonly client = inject(SavedLibraryClient);
  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  private readonly query = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  readonly tag = identityTag;
  readonly adventureId = computed(/** Route reuse must re-resolve the new identity. */ () => this.params().get('adventureId') ?? '');

  /**
   * Load adventure and playthroughs for the routed identity. When the identity changes, the
   * resource aborts the previous loader's signal and discards its result, so a slow reply for
   * the old adventure cannot win over the new one.
   */
  readonly loaded = resource({
    params: () => this.adventureId(),
    loader: async ({ params: adventureId, abortSignal }): Promise<SavedScope> => {
      const [adventure, playthroughs] = await Promise.all([
        this.client.adventure(adventureId, abortSignal), this.client.playthroughs(adventureId, abortSignal)]);
      return { adventure, playthroughs };
    },
  });

  /** Expose a scope only when it belongs to the current route identity (defence in depth). */
  readonly scope = computed(/** Never render a value loaded for another identity. */ (): SavedScope | undefined => {
    if (!this.loaded.hasValue()) return undefined;
    const value = this.loaded.value();
    return value.adventure.adventureId === this.adventureId() ? value : undefined;
  });

  /** Raw requested playthrough identity from the query string, or null when absent. */
  private readonly requested = computed(/** Malformed IDs are treated as foreign. */ () => this.query().get('playthrough'));

  /** The selected playthrough, only when it is listed under the current adventure. */
  readonly selected = computed(/** Selection never crosses adventures. */ () => {
    const id = this.requested();
    return id && isUuid(id) ? this.scope()?.playthroughs.find(/** Match identity only. */ (item) => item.campaignId === id) : undefined;
  });

  /** True when the route names a playthrough this adventure does not list. */
  readonly unknownPlaythrough = computed(/** Report rather than silently fall back. */ () => !!this.scope() && !!this.requested() && !this.selected());

  /** Localized load failure for the fallback panel. */
  readonly failure = computed(/** Only meaningful in the error state. */ () => failureMessage(this.loaded.error()));
}
