/**
 * The fights the player may start here (P13.2-B, ADR 0042): renders the encounters the combat projection offers outside a fight,
 * each with only what entry reveals — the observable participants with their side and starting zone, the player's starting zone,
 * the escape zones, the stakes and the opening-action policy — and lets the player start one, plainly or with an opening attack.
 *
 * A button only opens a review; the single `confirmed` output fires from the review's confirm button, never from choosing, from a
 * newer projection or from anything else. A review is discarded as soon as the projection moves to another revision or no longer
 * offers the entry. The host validates and sends; this component sends nothing itself. A projection outside a fight with no offer
 * renders one sentence saying so, and no projection or a fight renders nothing.
 * Template, participant and zone identifiers are shown literally: an offer carries no display text.
 */
import { ChangeDetectionStrategy, Component, computed, inject, input, LOCALE_ID, output, signal } from '@angular/core';
import { CombatView, EncounterOffer, EncounterStake } from './campaign-protocol';
import { EntryChoice, entryEntries, resolveEntry } from './combat-entry';
import {
  entryLabels, entryMessage, objectiveMessage, openingPolicyMessage, refusalMessage, sideMessage, startZoneMessage,
} from './combat-messages';

/** List the offered fights of one revision and hand an explicitly confirmed start to the host. */
@Component({
  selector: 'app-encounter-offers', changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .offers { margin-block: 1.5rem; }
    .offer { border: 1px solid var(--line, currentColor); border-radius: .4rem; padding: .25rem .75rem .5rem; margin-block: .75rem; }
    ul { margin: .25rem 0; padding-left: 1.25rem; }
    li { overflow-wrap: anywhere; margin-block: .25rem; }
    h4, h5 { font-size: 1rem; margin: .75rem 0 .25rem; }
    .starts { list-style: none; padding-left: 0; }
    .starts button { min-height: 2.5rem; max-width: 100%; margin: .15rem .25rem .15rem 0; overflow-wrap: anywhere; }
    .starts button[aria-pressed='true'] { font-weight: 700; outline: 2px solid var(--focus, Highlight); }
    button:focus-visible { outline: 3px solid var(--focus, Highlight); outline-offset: 2px; }
    .review { border: 1px solid var(--line, currentColor); border-radius: .4rem; padding: .25rem .75rem; margin-block: .75rem; }
  `,
  template: `
    @if (view()?.encounter === null && offers().length === 0) { <p class="muted" role="status" data-offers-none>{{ t.none }}</p> }
    @if (offers().length > 0) {
      <section class="offers" data-offers aria-labelledby="offers-title">
        <h3 id="offers-title">{{ t.title }}</h3>
        <p class="muted">{{ t.help }}</p>
        @for (offer of offers(); track offer.templateId) {
          <article class="offer" [attr.data-offer]="offer.templateId" [attr.aria-labelledby]="'offer-' + $index">
            <h4 [id]="'offer-' + $index">{{ offer.templateId }}</h4>
            <p data-offer-start>{{ startZone(offer.playerZone) }}</p>
            <h5>{{ t.present }}</h5>
            <ul data-offer-participants>
              @for (who of offer.participants; track who.participantId) {
                <li [attr.data-participant]="who.participantId" [attr.data-side]="who.side">{{ who.participantId }} · {{ sideText(who.side) }} · {{ who.zone }}</li>
              }
            </ul>
            <h5>{{ t.escape }}</h5>
            @if (offer.escapeZones.length === 0) { <p class="muted">{{ t.noEscape }}</p> }
            <ul data-offer-escape>@for (zone of offer.escapeZones; track zone) { <li>{{ zone }}</li> }</ul>
            <h5>{{ t.stakes }}</h5>
            @if (offer.objectives.length === 0) { <p class="muted">{{ t.noStakes }}</p> }
            <ul data-offer-stakes>@for (stake of offer.objectives; track stake.objectiveId) { <li [attr.data-stake]="stake.objectiveId">{{ stakeText(stake) }}</li> }</ul>
            <p data-offer-opening>{{ policyText(offer.openingPolicy) }}</p>
            <ul class="starts">
              @for (entry of entriesOf(offer); track entry.key) {
                <li><button type="button" [attr.data-entry]="entry.key" [disabled]="sending()" [attr.aria-pressed]="review()?.key === entry.key"
                  (click)="prepare(entry.key)">{{ label(entry.choice) }}</button></li>
              }
            </ul>
          </article>
        }
        <div aria-live="polite" data-entry-review-region>
          @if (review(); as shown) {
            <section class="review" aria-labelledby="entry-review-title" data-entry-review>
              <h5 id="entry-review-title">{{ t.previewTitle }}</h5>
              <p data-entry-review-action><strong>{{ label(shown.choice) }}</strong></p>
              <p>{{ t.binds }}</p>
              <p>
                <button type="button" data-entry-confirm [disabled]="sending()" (click)="confirm(shown.choice)">{{ t.confirm }}</button>
                <button type="button" data-entry-cancel [disabled]="sending()" (click)="cancel()">{{ t.cancel }}</button></p>
              @if (sending()) { <p role="status" data-entry-sending>{{ t.sending }}</p> }
            </section>
          }
        </div>
        @if (problemText(); as why) { <p class="notice" role="alert" data-entry-problem>{{ why }}</p> }
      </section>
    }
  `,
})
export class EncounterOffers {
  /** The projection of the shown revision; a fight, no projection or no offer renders nothing. */
  readonly view = input.required<CombatView | null>();
  /** True while the host sends a confirmed command; every control that could send another is disabled meanwhile. */
  readonly sending = input(false);
  /** Stable code of the last refusal the host met, or null; shown as a sentence, never as a code. */
  readonly problem = input<string | null>(null);
  /** The player confirmed the reviewed start; the host validates it again against its newest projection and sends it. */
  readonly confirmed = output<EntryChoice>();
  /** The player started a new review or backed out, so the host forgets the last refusal. */
  readonly dismissed = output<void>();
  private readonly numbers = new Intl.NumberFormat(inject(LOCALE_ID), { maximumFractionDigits: 0 });
  readonly t = entryLabels();
  /** Offers of the shown revision; always empty during a fight. */
  readonly offers = computed(() => { const view = this.view(); return view === null || view.encounter !== null ? [] : view.offers; });
  readonly entries = computed(() => entryEntries(this.view()));
  /** The entry under review and the revision it was chosen at; a newer revision discards it so a stale review never lingers. */
  readonly prepared = signal<{ readonly key: string; readonly revision: number } | null>(null);
  /** The reviewed entry, or null when none is prepared, the revision moved on or the projection no longer offers it. */
  readonly review = computed(() => {
    const mark = this.prepared();
    const view = this.view();
    if (mark === null || view === null || mark.revision !== view.stateRevision) return null;
    const entry = this.entries().find((candidate) => candidate.key === mark.key);
    return entry !== undefined && resolveEntry(view, entry.choice).ok ? entry : null;
  });
  readonly problemText = computed(() => { const code = this.problem(); return code === null ? null : refusalMessage(code); });

  sideText = sideMessage;
  startZone = startZoneMessage;
  policyText = openingPolicyMessage;

  /** The entries of one offer, in the order {@link entryEntries} lists them. */
  entriesOf(offer: EncounterOffer) { return this.entries().filter((entry) => entry.choice.templateId === offer.templateId); }
  label(choice: EntryChoice): string { return entryMessage(choice.templateId, choice.openingStrike); }
  stakeText(stake: EncounterStake): string { return objectiveMessage(stake, this.numbers.format(stake.rounds ?? 0)); }

  /** Review one entry: this only shows what starting it means and sends nothing. */
  prepare(key: string): void {
    const view = this.view();
    if (view === null || this.sending() || !this.entries().some((entry) => entry.key === key)) return;
    this.dismissed.emit();
    this.prepared.set({ key, revision: view.stateRevision });
  }

  /** Back out of the review without sending anything. */
  cancel(): void {
    this.dismissed.emit();
    this.prepared.set(null);
  }

  /** Hand the reviewed entry to the host; the only way this list ever causes a send, and only from the confirm button. */
  confirm(choice: EntryChoice): void {
    if (this.sending() || this.review() === null) return;
    this.confirmed.emit(choice);
  }
}
