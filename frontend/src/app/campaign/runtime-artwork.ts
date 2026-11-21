/**
 * Runtime artwork requests of one playthrough (P17.6-C, docs/23 section 4): the scene, portraits and items the player sees now,
 * the state of their art (`missing`, `pending`, `ready`, `failed`), the image budget, and an explicit button that asks for one
 * image.
 *
 * The server decides everything shown here: which subjects are in view, their public labels and the appearance text of a
 * request, which the browser never sends. Nothing is asked for on its own: a reading only lists, and only the player's button
 * press queues a job. While a job is pending the list is read again every few seconds (at most {@link MAX_POLLS} times per
 * revision); when a subject becomes ready the parent is told to re-read the artwork listing, so the new image appears where the
 * neutral fallback was. A failed read, a disabled or unavailable capability and a spent budget only change this section's text:
 * reading, Send and the fallbacks of the other panels are untouched.
 */
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, InjectionToken, input, output, signal, untracked } from '@angular/core';
import { CampaignProtocolError } from './campaign-protocol';
import { campaignErrorMessage } from './campaign-messages';
import { CampaignScope, CampaignTransport } from './campaign-session';
import { ImageAdmission, ImageCapability, ImageCategory, ImageRequests, ImageSubject, subjectKey } from './runtime-artwork-protocol';

/** Most re-reads while a job is pending, per revision; a job the server forgets cannot make the page poll forever. */
export const MAX_POLLS = 400;
/** Delay between re-reads while a job is pending; tests shorten it. */
export const RUNTIME_ARTWORK_POLL_MS = new InjectionToken<number>('Runtime artwork poll interval', {
  providedIn: 'root', factory: /** A few seconds keeps a pending state current without loading the engine. */ () => 3_000,
});

/** Section of the playthrough screen that lists runtime artwork and asks for one image on the player's request. */
@Component({
  selector: 'app-runtime-artwork', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="runtime-artwork" aria-labelledby="runtime-artwork-title" [attr.data-capability]="listing()?.capability ?? null">
      <h4 id="runtime-artwork-title" i18n="@@campaign.artwork.title">New artwork</h4>
      @if (listing(); as current) {
        @if (current.capability === 'enabled') {
          <p class="muted" i18n="@@campaign.artwork.help">Ask for an image of what you see now. Play goes on while it is made, and the story does not change.</p>
          <p class="muted" data-image-budget>{{ budgetText(current) }}</p>
        } @else { <p class="muted" data-image-capability>{{ capabilityText(current.capability) }}</p> }
        @if (failed()) { <p class="muted" data-image-list-failed>{{ listFailed }}</p> }
        @if (current.subjects.length > 0) {
          <ul class="runtime-artwork-list">
            @for (item of current.subjects; track key(item)) {
              <li [attr.data-image-subject]="key(item)" [attr.data-state]="item.state">
                <span>{{ categoryText(item.category) }}: {{ item.label }}</span> · <span data-image-state>{{ stateText(item) }}</span>
                @if (current.capability === 'enabled' && (item.state === 'missing' || item.state === 'failed')) {
                  <button type="button" data-request-image [disabled]="asking() !== null" [attr.aria-label]="askLabel(item)"
                    (click)="ask(item)">{{ item.state === 'failed' ? retryText : askText }}</button>
                }
                @if (notes()[key(item)]; as note) { <span class="muted" data-image-note> {{ note }}</span> }
              </li>
            }
          </ul>
        }
      } @else if (failed()) { <p class="muted" data-image-list-failed>{{ listFailed }}</p> }
      <p class="muted" role="status" aria-live="polite" data-image-announcement>{{ announcement() }}</p>
    </section>
  `,
})
export class RuntimeArtwork {
  /** The playthrough; a change forgets the previous one's listing, notes and pending reads. */
  readonly scope = input.required<CampaignScope>();
  /** Committed revision of the shown panels; null until they are read, so nothing is requested before the screen exists. */
  readonly revision = input<number | null>(null);
  /** Emitted when a subject's art became ready, so the parent re-reads the artwork listing. */
  readonly ready = output<void>();
  private readonly transport = inject(CampaignTransport);
  private readonly pollMs = inject(RUNTIME_ARTWORK_POLL_MS);
  readonly listing = signal<ImageRequests | null>(null);
  /** True when the last read of the listing failed; the last good listing, if any, stays shown. */
  readonly failed = signal(false);
  /** Key of the subject whose request is on its way; one request at a time. */
  readonly asking = signal<string | null>(null);
  /** The answer to the player's last request for a subject, by subject key. */
  readonly notes = signal<Readonly<Record<string, string>>>({});
  /** Polite announcement of the last state change the player caused. */
  readonly announcement = signal('');
  readonly askText = $localize`:@@campaign.artwork.ask:Ask for an image`;
  readonly retryText = $localize`:@@campaign.artwork.retry:Try again`;
  readonly listFailed = $localize`:@@campaign.artwork.listFailed:New artwork cannot be listed right now. The shown art and the built-in fallbacks stay.`;
  readonly key = subjectKey;
  private read: AbortController | undefined;
  private asks: AbortController | undefined;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private polls = 0;
  private scopeKey = '';

  /** Read the listing whenever the shown revision changes; leaving or switching playthrough aborts everything in flight. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Nothing outlives the screen. */ () => { this.stop(); this.asks?.abort(); });
    effect(/** A new revision may bring a new place, people or items into view. */ () => {
      const scope = this.scope();
      const revision = this.revision();
      untracked(/** Work starts without subscribing to its own signals. */ () => {
        const key = JSON.stringify([scope.adventureId, scope.campaignId]);
        if (key !== this.scopeKey) {
          this.scopeKey = key;
          this.asks?.abort();
          this.asks = undefined;
          this.listing.set(null);
          this.notes.set({});
          this.failed.set(false);
          this.announcement.set('');
          this.asking.set(null);
        }
        this.stop();
        if (revision === null) return;
        this.polls = 0;
        void this.load(scope);
      });
    });
  }

  /**
   * Ask for the image of one subject. Runs only from its button. The answer replaces the subject's state; a refusal is shown
   * beside it and changes nothing else; a lost reply re-reads the list, and asking again is safe because an equal request joins
   * the job already running.
   */
  async ask(item: ImageSubject): Promise<void> {
    if (this.asking() !== null) return;
    const scope = this.scope();
    const key = subjectKey(item);
    const asks = this.asks ??= new AbortController();
    this.asking.set(key);
    this.note(key, '');
    try {
      const answer = await this.transport.requestImage(scope, item.category, item.subject, asks.signal);
      if (asks.signal.aborted) return;
      this.replace(answer.subject);
      this.note(key, admissionText(answer.admission));
      if (answer.subject.state === 'ready') { this.announcement.set(readyText(item.label)); this.ready.emit(); }
      if (answer.subject.state === 'pending') this.restart(scope, this.pollMs);
    } catch (error) {
      if (asks.signal.aborted) return;
      if (error instanceof CampaignProtocolError && error.kind === 'api') {
        this.note(key, error.code === 'ARTWORK_SUBJECT_UNAVAILABLE'
          ? $localize`:@@campaign.artwork.outOfView:This is no longer in view, so no image was asked for.`
          : campaignErrorMessage(error.code));
      } else {
        this.note(key, $localize`:@@campaign.artwork.lost:The request may not have arrived. The list is read again; asking again is safe.`);
      }
      this.restart(scope, 0);
    } finally { if (!asks.signal.aborted) this.asking.set(null); }
  }

  /** Localized helpers for the template; each returns display text only. */
  capabilityText(capability: ImageCapability): string {
    return capability === 'disabled' ? disabledText() : unavailableText();
  }

  budgetText(current: ImageRequests): string {
    const { charged, limit } = current.budget;
    return $localize`:@@campaign.artwork.budget:Images asked for in this period: ${charged}:charged: of ${limit}:limit:.`;
  }

  categoryText(category: ImageCategory): string {
    return category === 'scene' ? $localize`:@@campaign.artwork.scene:Scene`
      : category === 'portrait' ? $localize`:@@campaign.artwork.portrait:Portrait` : $localize`:@@campaign.artwork.item:Item`;
  }

  stateText(item: ImageSubject): string {
    switch (item.state) {
      case 'pending': return $localize`:@@campaign.artwork.pending:Being made…`;
      case 'ready': return $localize`:@@campaign.artwork.ready:Ready`;
      case 'failed': return failureText(item.errorCode);
      default: return $localize`:@@campaign.artwork.missing:No image yet`;
    }
  }

  /** Accessible name of a subject's button; it starts with the visible text, so speech control finds it. */
  askLabel(item: ImageSubject): string {
    const label = item.label;
    return item.state === 'failed' ? $localize`:@@campaign.artwork.retryLabel:Try again to make an image of ${label}:label:`
      : $localize`:@@campaign.artwork.askLabel:Ask for an image of ${label}:label:`;
  }

  /** Abort the pending read and its timer. */
  private stop(): void {
    this.read?.abort();
    this.read = undefined;
    clearTimeout(this.timer);
    this.timer = undefined;
  }

  /** Read the list again after `delay`, with a fresh poll allowance because the player just asked. */
  private restart(scope: CampaignScope, delay: number): void {
    this.stop();
    this.polls = 0;
    const read = new AbortController();
    this.read = read;
    this.timer = setTimeout(/** Only the newest schedule may read. */ () => { if (this.read === read) void this.load(scope); }, delay);
  }

  /** Read the listing once; while anything is pending, schedule the next read. */
  private async load(scope: CampaignScope): Promise<void> {
    this.stop();
    const read = new AbortController();
    this.read = read;
    try {
      const next = await this.transport.imageRequests(scope, read.signal);
      if (read.signal.aborted) return;
      this.apply(next);
      this.failed.set(false);
      if (next.subjects.some((item) => item.state === 'pending')) this.schedule(scope, read);
    } catch (error) {
      if (read.signal.aborted) return;
      this.failed.set(true);
      // A missing scope, a denial or an invalid reply will not get better by itself; only a pending job is worth waiting for.
      const permanent = error instanceof CampaignProtocolError && (error.kind === 'invalid' || error.status === 404 || error.status === 403);
      if (!permanent && this.listing()?.subjects.some((item) => item.state === 'pending')) this.schedule(scope, read);
    }
  }

  private schedule(scope: CampaignScope, read: AbortController): void {
    if (this.polls >= MAX_POLLS) return;
    this.polls++;
    this.timer = setTimeout(/** A newer read or a scope change supersedes this one. */ () => { if (this.read === read) void this.load(scope); }, this.pollMs);
  }

  /** Show a new listing and tell the parent when a pending subject became ready. */
  private apply(next: ImageRequests): void {
    const before = new Map((this.listing()?.subjects ?? []).map((item) => [subjectKey(item), item.state]));
    this.listing.set(next);
    const done = next.subjects.filter((item) => item.state === 'ready' && before.get(subjectKey(item)) === 'pending');
    const broke = next.subjects.filter((item) => item.state === 'failed' && before.get(subjectKey(item)) === 'pending');
    if (done.length > 0) { this.announcement.set(readyText(done.map((item) => item.label).join(', '))); this.ready.emit(); }
    else if (broke.length > 0) this.announcement.set(failedText(broke.map((item) => item.label).join(', ')));
  }

  /** Replace one subject's state in the shown listing. */
  private replace(changed: ImageSubject): void {
    const current = this.listing();
    if (current === null) return;
    this.listing.set({ ...current, subjects: current.subjects.map((item) => subjectKey(item) === subjectKey(changed) ? changed : item) });
  }

  private note(key: string, text: string): void { this.notes.update((notes) => ({ ...notes, [key]: text })); }
}

function disabledText(): string {
  return $localize`:@@campaign.artwork.disabled:Making new images is turned off. The built-in art and fallbacks stay, and play goes on.`;
}

function unavailableText(): string {
  return $localize`:@@campaign.artwork.unavailable:No image service is set up, so no new image can be made. The built-in art and fallbacks stay, and play goes on.`;
}

function readyText(label: string): string { return $localize`:@@campaign.artwork.readyNotice:Image ready: ${label}:label:`; }

function failedText(label: string): string { return $localize`:@@campaign.artwork.failedNotice:No image could be made: ${label}:label:`; }

/** Why a job ended without an image; codes outside the known ones get one general sentence. */
function failureText(code: string | null): string {
  switch (code) {
    case 'image-job-timeout': return $localize`:@@campaign.artwork.failedTimeout:Could not be made: it took too long.`;
    case 'image-job-stale': return $localize`:@@campaign.artwork.failedStale:Could not be made: it arrived after things changed, so it was not used.`;
    default: return $localize`:@@campaign.artwork.failed:Could not be made.`;
  }
}

/** The answer to the player's request, in words. */
function admissionText(admission: ImageAdmission): string {
  switch (admission) {
    case 'queued': return $localize`:@@campaign.artwork.queued:Asked. The image is being made; you can keep playing.`;
    case 'coalesced': return $localize`:@@campaign.artwork.coalesced:This image is already being made; you can keep playing.`;
    case 'reused': return $localize`:@@campaign.artwork.reused:This image already exists and is shown.`;
    case 'stale-appearance': return $localize`:@@campaign.artwork.newer:A newer image of this is already shown.`;
    case 'disabled': return disabledText();
    case 'unavailable': return unavailableText();
    case 'queue-full': return $localize`:@@campaign.artwork.queueFull:Too many images are waiting for this playthrough. Try again when one is ready.`;
    default: return $localize`:@@campaign.artwork.budgetSpent:The image limit for this period is used up. The fallback stays; try again later.`;
  }
}
