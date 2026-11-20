/**
 * Playthrough creation for a saved adventure (`/adventures/<uuid>/create`, P14.1-C, ADR 0047).
 *
 * The screen reads the adventure and its sealed content revisions (`GET /api/v1/packs`), validates a playthrough
 * title and a character name locally, and sends `POST /api/v1/campaigns` only when the player selects
 * "Create playthrough". Choices are the name with the adventure's default allocations (no archetype, empty
 * attribute and skill maps), the only creation metadata the read API exposes today. The request identity follows
 * the narrative view's rule: a lost reply keeps it, so repeating the same choices returns the stored playthrough
 * instead of creating a second one; a refusal or changed choices start a new identity. A created playthrough opens
 * in the live campaign view at `/saved/<adventureId>?playthrough=<campaignId>`.
 */
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, LOCALE_ID, resource, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CampaignProtocolError } from '../campaign/campaign-protocol';
import { CampaignTransport, CreationCommand } from '../campaign/campaign-session';
import { InstalledPack, SavedAdventure, SavedLibraryClient } from './saved-library-client';
import { failureMessage } from './saved-library';

/** Character name bounds shown to the player; the server accepts up to 120 after the same blank check. */
const NAME_MAX = 60;
/** Playthrough title bound of the campaign table. */
const TITLE_MAX = 200;

/** Return the localized explanation of a failed creation; unknown codes use one generic sentence. */
export function creationProblem(code: string): string {
  switch (code) {
    case 'LOST': return $localize`:@@newPlaythrough.lost:The reply was lost, so the playthrough may already exist. Select Create playthrough again without changing anything: the same request is repeated and no second playthrough is created.`;
    case 'INVALID': return $localize`:@@newPlaythrough.invalidReply:The engine sent a reply this interface cannot verify. Open the saved adventure to check whether the playthrough exists.`;
    case 'CREATION_CHOICES_INVALID': return $localize`:@@newPlaythrough.choicesInvalid:The adventure did not accept these choices. Nothing was created.`;
    case 'CREATION_SCOPE_UNAVAILABLE': return $localize`:@@newPlaythrough.scopeUnavailable:This adventure or its content is no longer available. Nothing was created.`;
    case 'CREATION_REQUEST_CONFLICT': return $localize`:@@newPlaythrough.conflict:This request was already used with other choices. Nothing was created; select Create playthrough to send a new request.`;
    case 'CREATION_BUSY': return $localize`:@@newPlaythrough.busy:The engine is busy. Nothing was created; try again in a moment.`;
    case 'LOCAL_ADMISSION_DENIED': return $localize`:@@saved.denied:This browser session was not admitted. Reload the page to try again.`;
    case 'LOCAL_OWNER_UNCONFIGURED': return $localize`:@@saved.unconfigured:The local owner is not configured, so saved adventures cannot be read.`;
    default: return $localize`:@@newPlaythrough.unavailable:Creating a playthrough is unavailable right now. Nothing was created; try again later.`;
  }
}

/** Adventure and sealed revisions loaded together for one adventure identity. */
interface CreationScope {
  readonly adventure: SavedAdventure;
  readonly packs: readonly InstalledPack[];
}

/** Create one playthrough of the routed saved adventure on explicit request; see the module comment. */
@Component({
  selector: 'app-playthrough-creation', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (scope(); as current) {
      <section class="panel narrow" [attr.data-adventure-id]="current.adventure.adventureId">
        <h1 i18n="@@newPlaythrough.title">New playthrough</h1>
        <p><strong [attr.lang]="current.adventure.sourceLocale">{{ current.adventure.title }}</strong></p>
        <p i18n="@@newPlaythrough.intro">Nothing is saved until you select Create playthrough. Attributes and skills start at the adventure’s defaults.</p>
        @if (current.packs.length === 0) {
          <p class="notice" role="alert" i18n="@@newPlaythrough.noContent">This adventure has no installed content yet, so a playthrough cannot be created.</p>
        } @else {
          <form (submit)="create($event)" novalidate>
            <fieldset><legend i18n="@@newPlaythrough.content">Adventure content</legend>
              @for (pack of current.packs; track pack.contentRevisionId) {
                <div class="pack-choice" [attr.data-revision-id]="pack.contentRevisionId">
                  <input type="radio" name="revision" [id]="'revision-' + pack.contentRevisionId" [checked]="pack.contentRevisionId === chosen(current)"
                    [disabled]="sending()" (change)="revision.set(pack.contentRevisionId)" />
                  <label [for]="'revision-' + pack.contentRevisionId"><span [attr.lang]="pack.sourceLocale">{{ pack.title }}</span> <span class="muted">{{ pack.packVersion }}</span></label>
                  <p class="muted" [attr.lang]="pack.sourceLocale">{{ pack.summary }}</p>
                  <p class="muted"><span i18n="@@newPlaythrough.authors">Authors:</span> <span [attr.lang]="pack.sourceLocale">{{ pack.authors.join(', ') }}</span>
                    · <span i18n="@@newPlaythrough.license">License:</span> <span [attr.lang]="pack.sourceLocale">{{ pack.license }}</span></p>
                </div>
              }
            </fieldset>
            @if (attempted() && chosen(current) === null) { <p role="alert" i18n="@@newPlaythrough.contentError">Choose the adventure content to play.</p> }
            <label for="playthrough-title" i18n="@@newPlaythrough.titleLabel">Playthrough title</label>
            <input id="playthrough-title" [value]="title()" [disabled]="sending()" (input)="title.set(value($event))" aria-describedby="playthrough-title-help" />
            <p id="playthrough-title-help" i18n="@@newPlaythrough.titleHelp">Enter a title of 1–200 characters.</p>
            @if (attempted() && !titleValid()) { <p role="alert" i18n="@@newPlaythrough.titleError">Enter a title of 1–200 characters.</p> }
            <label for="character-name" i18n="@@newPlaythrough.nameLabel">Character name</label>
            <input id="character-name" [value]="name()" [disabled]="sending()" (input)="name.set(value($event))" aria-describedby="character-name-help" />
            <p id="character-name-help" i18n="@@creation.nameError">Enter a name of 1–60 characters.</p>
            @if (attempted() && !nameValid()) { <p role="alert" i18n="@@creation.nameError">Enter a name of 1–60 characters.</p> }
            <button type="submit" [disabled]="sending()" i18n="@@newPlaythrough.create">Create playthrough</button>
          </form>
          @if (sending()) { <p role="status" i18n="@@newPlaythrough.sending">Creating the playthrough…</p> }
          @if (problem(); as code) { <p class="notice" role="alert">{{ problemText(code) }}</p> }
        }
        <a [routerLink]="['/saved', current.adventure.adventureId]" i18n="@@newPlaythrough.back">Back to the saved adventure</a>
      </section>
    } @else if (loaded.isLoading()) {
      <section class="panel"><p role="status" i18n="@@saved.loadingOne">Loading saved adventure…</p></section>
    } @else {
      <section class="panel"><h1 i18n="@@workspace.missing">Adventure unavailable</h1><p role="alert">{{ failure() }}</p><a routerLink="/library" i18n="@@workspace.return">Return to library</a></section>
    }
  `,
})
export class PlaythroughCreation {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly client = inject(SavedLibraryClient);
  private readonly transport = inject(CampaignTransport);
  // The interface language is the narration language asked for; a pack without it falls back to its source language.
  private readonly narrativeLocale: 'en' | 'it' = inject(LOCALE_ID).toLowerCase().startsWith('it') ? 'it' : 'en';
  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  // Aborted when the screen is destroyed so a late creation reply cannot navigate a detached screen.
  private readonly lifetime = new AbortController();
  readonly adventureId = computed(/** Route reuse must re-resolve the new identity. */ () => this.params().get('adventureId') ?? '');
  readonly title = signal('');
  readonly name = signal('');
  /** The revision the player selected, or null to use the only one listed. */
  readonly revision = signal<string | null>(null);
  readonly attempted = signal(false);
  readonly sending = signal(false);
  /** `LOST`, `INVALID` or the stable code of the last refusal, or null. */
  readonly problem = signal<string | null>(null);
  readonly problemText = creationProblem;
  /** The identity of the creation in flight or lost, kept so repeating the same choices is idempotent. */
  private attempt: { readonly key: string; readonly requestId: string } | null = null;

  /**
   * Load the adventure and its sealed revisions for the routed identity; an identity change aborts the previous
   * loader, so a slow reply for one adventure can never offer its revisions under another.
   */
  readonly loaded = resource({
    params: () => this.adventureId(),
    loader: async ({ params: adventureId, abortSignal }): Promise<CreationScope> => {
      const [adventure, packs] = await Promise.all([
        this.client.adventure(adventureId, abortSignal), this.client.packs(adventureId, abortSignal)]);
      return { adventure, packs };
    },
  });

  /** Expose a scope only when it belongs to the current route identity (defence in depth). */
  readonly scope = computed(/** Never render a value loaded for another identity. */ (): CreationScope | undefined => {
    if (!this.loaded.hasValue()) return undefined;
    const value = this.loaded.value();
    return value.adventure.adventureId === this.adventureId() ? value : undefined;
  });

  readonly titleValid = computed(/** Same blank rule as the server, after trimming. */ () => valid(this.title(), TITLE_MAX));
  readonly nameValid = computed(/** Same blank rule as the server, after trimming. */ () => valid(this.name(), NAME_MAX));
  /** Localized load failure for the fallback panel. */
  readonly failure = computed(/** Only meaningful in the error state. */ () => failureMessage(this.loaded.error()));

  /** Abort pending work when the screen is left. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Detach pending requests. */ () => this.lifetime.abort());
  }

  /** The selected revision of this scope: the player's choice when listed, otherwise the only revision, else none. */
  chosen(scope: CreationScope): string | null {
    const picked = this.revision();
    if (picked && scope.packs.some(/** Only a listed revision counts. */ (pack) => pack.contentRevisionId === picked)) return picked;
    return scope.packs.length === 1 ? scope.packs[0].contentRevisionId : null;
  }

  /** Read the current text of an input event. */
  value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /**
   * Submit the creation on the player's explicit request. Invalid input sends nothing. The same choices after a
   * lost reply reuse the request identity; any other choices, or a refusal, get a new one.
   */
  async create(event: Event): Promise<void> {
    event.preventDefault();
    const scope = this.scope();
    if (!scope || this.sending()) return;
    this.attempted.set(true);
    const revision = this.chosen(scope);
    if (!this.titleValid() || !this.nameValid() || revision === null) return;
    const body = {
      adventureId: scope.adventure.adventureId, contentRevisionId: revision, title: this.title().trim(),
      narrativeLocale: this.narrativeLocale, choices: { name: this.name().trim(), attributes: {}, skills: {} },
    };
    const key = JSON.stringify(body);
    if (this.attempt?.key !== key) this.attempt = { key, requestId: globalThis.crypto.randomUUID() };
    const command: CreationCommand = { requestId: this.attempt.requestId, ...body };
    this.sending.set(true);
    this.problem.set(null);
    try {
      const created = await this.transport.create(command, this.lifetime.signal);
      if (this.lifetime.signal.aborted) return;
      this.attempt = null;
      await this.router.navigate(['/saved', scope.adventure.adventureId], { queryParams: { playthrough: created.campaignId } });
    } catch (error) {
      if (this.lifetime.signal.aborted) return;
      if (error instanceof CampaignProtocolError && error.kind === 'api') {
        // A refusal is final for this identity: nothing was stored, so the next try is a new request.
        this.attempt = null;
        this.problem.set(error.code ?? 'UNAVAILABLE');
      } else {
        // A lost or unverifiable reply keeps the identity: the playthrough may exist, and repeating cannot duplicate it.
        this.problem.set(error instanceof CampaignProtocolError && error.kind === 'invalid' ? 'INVALID' : 'LOST');
      }
    } finally { this.sending.set(false); }
  }
}

/** True for text that is non-blank and at most `max` characters after trimming. */
function valid(text: string, max: number): boolean {
  const trimmed = text.trim();
  return trimmed.length > 0 && trimmed.length <= max;
}
