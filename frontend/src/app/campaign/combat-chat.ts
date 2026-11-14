/**
 * Persistent combat dialogue chat (P12.2-B, docs/13 "Combat chat" and "P12.2-B combat chat"): an attributed transcript of the fight's
 * public conversation, a recipient selector of known participants, a free-text composer with a separate review-and-send control,
 * editable quick replies, and the talk, negotiation, scene and offer-answer intents the server projection lists.
 *
 * The chat only prepares and previews. It emits one `confirmed` choice from its confirm button, never from writing, choosing a
 * recipient, filling a quick reply, a newer projection or a reconnect; the host validates the choice again against its newest
 * projection and sends it. A quick reply fills the draft and never sends or replaces a draft the player wrote without an explicit
 * "Replace my words". Words are speech-only: they establish no agreement, a recipient's refusal or counteroffer is the server's
 * receipt, and only an explicit answer to the offer on the table binds the player. The draft is held here, outside any one
 * projection, so a re-read, a newer revision or a lost reply never replaces it; it is tied to its encounter and dropped for another.
 * Nothing is queued for a later turn: outside the player's decision the composer stays editable and only the review is unavailable.
 */
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, LOCALE_ID, output, signal, untracked } from '@angular/core';
import { CombatEncounter, CombatView, JournalEntry } from './campaign-protocol';
import { choiceKey, MAX_SPEECH_TEXT, resolveChoice, TacticalChoice, tacticalEntries, TacticalEntry } from './combat-actions';
import {
  charactersMessage, checkMessage, chatBlockMessage, choiceMessage, combatLabels, offerMessage, participantName, priceMessage,
  quickReplies, refusalMessage, replaceMessage, sideMessage, spendMessage, speechSpendMessage, termsMessage, transcriptKindMessage,
  disabledMessage, wordsMessage,
} from './combat-messages';

/** What a transcript line is, shown as a text label so the speaker's role never depends on color. */
export type TranscriptKind = 'said' | 'attempt' | 'reply' | 'decision' | 'result';
/** One public line of the fight's conversation, worded by the server. */
export interface TranscriptLine { readonly sequence: number; readonly kind: TranscriptKind; readonly statement: string }

/** Journal event types that belong to the combat conversation; every other event stays in the chronicle only. */
const CONVERSATION: Readonly<Record<string, TranscriptKind>> = {
  COMBAT_SPEECH: 'said', INTERACTION_ATTEMPTED: 'attempt', NEGOTIATION_RESPONDED: 'reply', OFFER_ANSWERED: 'decision',
  SOCIAL_CHECK_RESOLVED: 'result', OFFER_LAPSED: 'result', PARTICIPANT_SURRENDERED: 'result',
};
/** Most lines shown at once; older ones remain in the chronicle. */
const MAX_LINES = 40;

/**
 * The public conversation of the newest fight in a journal page, oldest first.
 *
 * Each line is the server's own sentence, which names who spoke or answered, so attribution is never reconstructed by the page.
 * Lines before the newest `ENCOUNTER_STARTED` of the page belong to an earlier fight and are left out; when the page holds no such
 * start, every conversation line of the page is kept up to the bound. The input order does not matter.
 */
export function transcript(journal: readonly JournalEntry[] | null): TranscriptLine[] {
  const ordered = [...(journal ?? [])].sort((a, b) => a.sequence - b.sequence);
  const start = ordered.map((entry) => entry.type).lastIndexOf('ENCOUNTER_STARTED');
  return ordered.slice(start + 1).flatMap((entry): TranscriptLine[] => {
    const kind = CONVERSATION[entry.type];
    return kind === undefined ? [] : [{ sequence: entry.sequence, kind, statement: entry.statement }];
  }).slice(-MAX_LINES);
}

/** The participants other than the player the conversation can name: those still in the fight or that surrendered. */
export function knownRecipients(fight: CombatEncounter): string[] {
  return fight.participants.filter((who) => who.side !== 'player' && (who.status === 'ACTIVE' || who.status === 'SURRENDERED')).map((who) => who.participantId);
}

/** Show the fight's conversation and prepare words, social and scene actions and answers; renders nothing outside a fight. */
@Component({
  selector: 'app-combat-chat', changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    details { margin-block: .75rem; border: 1px solid var(--line, currentColor); border-radius: .4rem; padding: .25rem .75rem .5rem; }
    summary { cursor: pointer; font-weight: 700; padding-block: .4rem; }
    summary:focus-visible, .lines:focus-visible { outline: 3px solid var(--focus, Highlight); outline-offset: 2px; }
    h4, h5 { margin: .75rem 0 .25rem; font-size: 1rem; }
    ul, ol { margin: .25rem 0; padding-left: 1.25rem; }
    ul.plain { list-style: none; padding-left: 0; }
    li { overflow-wrap: anywhere; margin-block: .25rem; }
    .lines { max-height: 14rem; overflow-y: auto; }
    .reason, .muted { color: var(--muted, inherit); }
    label { display: block; font-weight: 700; margin-block: .5rem .15rem; }
    select, textarea { width: 100%; max-width: 100%; box-sizing: border-box; }
    button { min-height: 2.5rem; max-width: 100%; margin: .15rem .25rem .15rem 0; overflow-wrap: anywhere; }
    button:focus-visible, select:focus-visible, textarea:focus-visible { outline: 3px solid var(--focus, Highlight); outline-offset: 2px; }
    button[aria-pressed='true'] { font-weight: 700; outline: 2px solid var(--focus, Highlight); }
    .review { border: 1px solid var(--line, currentColor); border-radius: .4rem; padding: .25rem .75rem; margin-block: .75rem; }
  `,
  template: `
    @if (fight(); as f) {
      <details open class="chat" data-chat>
        <summary>{{ t.chatTitle }}</summary>
        <p class="muted">{{ t.chatHelp }}</p>

        <h4 id="combat-chat-lines-title">{{ t.transcriptTitle }}</h4>
        <ol class="lines" role="log" aria-live="polite" tabindex="0" aria-labelledby="combat-chat-lines-title" [attr.lang]="journalLocale()" data-chat-lines>
          @for (line of lines(); track line.sequence) {
            <li [attr.data-line-kind]="line.kind"><strong>{{ kindText(line.kind) }}</strong> · {{ line.statement }}</li>
          }
        </ol>
        @if (lines().length === 0) { <p class="muted" data-chat-empty>{{ t.noTranscript }}</p> }

        <label for="combat-chat-recipient">{{ t.recipientLabel }}</label>
        <select #who id="combat-chat-recipient" (change)="chooseRecipient(who.value)">
          <option value="" [selected]="recipient() === ''">{{ t.noRecipient }}</option>
          @for (id of recipients(); track id) { <option [value]="id" [selected]="id === recipient()">{{ recipientText(f, id) }}</option> }
        </select>
        <label for="combat-chat-words">{{ t.draftLabel }}</label>
        <textarea #words id="combat-chat-words" [value]="text()" [attr.maxlength]="maxText" rows="3"
          aria-describedby="combat-chat-count combat-chat-block" (input)="write(words.value)"></textarea>
        <p id="combat-chat-count" class="muted" data-chat-count>{{ countText() }}</p>
        <p id="combat-chat-block" class="muted" role="status" data-chat-block>{{ blockText() }}</p>
        <p>
          <button type="button" data-chat-review [disabled]="blocked() !== null || sending()" (click)="prepare(choice())">{{ t.reviewSend }}</button>
          <button type="button" data-chat-clear [disabled]="text() === ''" (click)="clear()">{{ t.clearDraft }}</button>
        </p>

        <h5>{{ t.quickTitle }}</h5>
        @if (recipient() === '') { <p class="muted" data-chat-quick-none>{{ t.quickNeedsRecipient }}</p> }
        <ul class="plain" data-chat-quick>
          @for (reply of quick(); track reply) { <li><button type="button" (click)="fill(reply)">{{ reply }}</button></li> }
        </ul>
        @if (replacement(); as proposed) {
          <div role="group" aria-live="polite" data-chat-replace>
            <p>{{ replaceText(proposed) }}</p>
            <button type="button" data-chat-replace-yes (click)="replaceDraft()">{{ t.replace }}</button>
            <button type="button" data-chat-replace-no (click)="keepDraft()">{{ t.keep }}</button>
          </div>
        }

        <h4>{{ t.intentsTitle }}</h4>
        @if (f.options?.offer; as offer) {
          <section data-chat-offer>
            <p>{{ offerText(offer.terms.termsId, offer.recipientId) }} {{ offer.canAccept ? t.canAccept : t.cannotAccept }}</p>
            <ul class="plain">
              @for (entry of answers(); track entry.key) {
                <li><button type="button" [attr.data-chat-choice]="entry.key" [disabled]="entry.disabled !== null || sending()"
                  [attr.aria-pressed]="review()?.key === entry.key" (click)="prepare(entry.choice)">{{ label(entry.choice) }}</button>
                  @if (entry.disabled; as why) { <span class="reason"> · {{ reasonText(why) }}</span> }</li>
              }
            </ul>
          </section>
        }
        @if (recipient() === '' && hasSocial()) { <p class="muted" data-chat-intents-none>{{ t.intentsNeedRecipient }}</p> }
        <ul class="plain" data-chat-intents>
          @for (entry of intents(); track entry.key) {
            <li><button type="button" [attr.data-chat-choice]="entry.key" [disabled]="sending()"
              [attr.aria-pressed]="review()?.key === entry.key" (click)="prepare(entry.choice)">{{ label(entry.choice) }}</button></li>
          }
        </ul>
        @if (intents().length === 0 && answers().length === 0 && !(recipient() === '' && hasSocial())) { <p class="muted" data-chat-no-intents>{{ t.noIntents }}</p> }

        <div aria-live="polite" data-chat-review-region>
          @if (review(); as shown) {
            <section class="review" aria-labelledby="combat-chat-review-title" data-chat-review>
              <h5 id="combat-chat-review-title">{{ t.previewTitle }}</h5>
              <p data-chat-review-action><strong>{{ label(shown.preview.choice) }}</strong></p>
              <ul>
                @if (shown.preview.speech; as said) {
                  <li data-chat-review-words>{{ wordsText(said.text) }}</li>
                  <li data-chat-review-cost>{{ speechCostText(said.recipientId, said.remainingAfter) }}</li>
                } @else {
                  <li data-chat-review-points>{{ spendText(shown.preview.points, shown.preview.pointsAfter) }}</li>
                }
                @if (shown.preview.check; as check) { <li data-chat-review-check>{{ checkText(check) }}</li> }
                @if (shown.preview.terms; as terms) { <li data-chat-review-terms>{{ termsText(terms) }}</li> }
                @if (shown.preview.decision === 'accept') { <li data-chat-review-binding>{{ t.acceptBinds }}</li> }
                @if (shown.preview.decision === 'decline') { <li data-chat-review-binding>{{ t.declineFree }}</li> }
                <li class="muted">{{ t.previewFinal }}</li>
              </ul>
              <p>
                <button type="button" data-chat-confirm [disabled]="sending()" (click)="confirm()">{{ t.confirm }}</button>
                <button type="button" data-chat-cancel [disabled]="sending()" (click)="cancel()">{{ t.cancel }}</button></p>
              @if (sending()) { <p role="status" data-chat-sending>{{ t.sending }}</p> }
            </section>
          }
        </div>
        @if (review() !== null && problemText(); as why) { <p class="notice" role="alert" data-chat-problem>{{ why }}</p> }
      </details>
    }
  `,
})
export class CombatChat {
  /** The projection of the shown revision; null or a view without a fight renders nothing. */
  readonly view = input.required<CombatView | null>();
  /** The newest journal page, from which the public conversation is read; null before it is read. */
  readonly journal = input<readonly JournalEntry[] | null>(null);
  /** Language of the journal's sentences, so they are announced correctly. */
  readonly journalLocale = input<'en' | 'it' | null>(null);
  /** True while the host sends a confirmed choice; nothing here can start another meanwhile. */
  readonly sending = input(false);
  /** Stable code of the host's last refusal, or null. */
  readonly problem = input<string | null>(null);
  /** The player confirmed the reviewed choice; the host validates it again against its newest projection and sends it. */
  readonly confirmed = output<TacticalChoice>();
  /** The player started a new review or backed out, so the host forgets the last refusal. */
  readonly dismissed = output<void>();

  private readonly numbers = new Intl.NumberFormat(inject(LOCALE_ID), { maximumFractionDigits: 0 });
  readonly t = combatLabels();
  readonly maxText = MAX_SPEECH_TEXT;
  readonly fight = computed(() => this.view()?.encounter ?? null);
  /** The unsent draft and the encounter it was written in; kept while the projection is re-read and dropped for another fight. */
  private readonly stored = signal<{ readonly encounterId: string; readonly recipient: string; readonly text: string } | null>(null);
  /** A quick reply waiting for the player to agree to replace a draft they wrote. */
  readonly replacement = signal<string | null>(null);
  /** The choice under review and the revision it was prepared at; a newer revision discards the review but never the draft. */
  private readonly reviewing = signal<{ readonly choice: TacticalChoice; readonly revision: number } | null>(null);
  /** The last choice confirmed here, so a committed answer can be recognized and its draft cleared. */
  private submitted: { readonly choice: TacticalChoice; readonly revision: number } | null = null;

  readonly lines = computed(() => transcript(this.journal()));
  readonly recipients = computed(() => { const fight = this.fight(); return fight === null ? [] : knownRecipients(fight); });
  private readonly draft = computed(() => {
    const kept = this.stored();
    return kept !== null && kept.encounterId === this.fight()?.encounterId ? kept : null;
  });
  /** The chosen recipient, or empty: a recipient who left the known list is never silently replaced by another. */
  readonly recipient = computed(() => { const id = this.draft()?.recipient ?? ''; return this.recipients().includes(id) ? id : ''; });
  readonly text = computed(() => this.draft()?.text ?? '');
  readonly choice = computed((): TacticalChoice => ({ kind: 'speak', recipientId: this.recipient(), text: this.text() }));
  /** Why the draft cannot be reviewed now, or null; this never changes the draft. */
  readonly blocked = computed(() => { const fight = this.fight(); if (fight === null) return 'NOT_YOUR_TURN'; const result = resolveChoice(fight, this.choice()); return result.ok ? null : result.refusal; });
  readonly blockText = computed(() => { const why = this.blocked(); return why === null ? '' : chatBlockMessage(why) ?? ''; });
  readonly countText = computed(() => charactersMessage(this.n(this.text().length), this.n(MAX_SPEECH_TEXT)));
  readonly quick = computed(() => {
    const id = this.recipient();
    const side = this.fight()?.participants.find((who) => who.participantId === id)?.side;
    return side === undefined ? [] : quickReplies(side);
  });
  private readonly entries = computed(() => { const fight = this.fight(); return fight === null ? [] : tacticalEntries(fight); });
  readonly answers = computed(() => this.entries().filter((entry) => entry.choice.kind === 'answer'));
  /** Social actions are shown only for the chosen recipient, scene actions always: no recipient is invented for ambiguous text. */
  readonly intents = computed((): TacticalEntry[] => {
    const fight = this.fight();
    const recipient = this.recipient();
    return this.entries().filter((entry) => {
      const choice = entry.choice;
      if (choice.kind !== 'interact') return false;
      const kind = fight?.options?.actions.find((action) => action.actionId === choice.actionId)?.kind;
      return kind === 'scene' || (kind === 'social' && choice.targetId !== null && choice.targetId === recipient);
    });
  });
  readonly hasSocial = computed(() => this.fight()?.options?.actions.some((action) => action.kind === 'social') ?? false);
  /** The review of the prepared choice with its costs, or null when none is prepared or the projection no longer allows it. */
  readonly review = computed(() => {
    const mark = this.reviewing();
    const view = this.view();
    const fight = this.fight();
    if (mark === null || view === null || fight === null || mark.revision !== view.stateRevision) return null;
    const resolved = resolveChoice(fight, mark.choice);
    return resolved.ok ? { key: choiceKey(mark.choice), preview: resolved.preview } : null;
  });
  readonly problemText = computed(() => { const code = this.problem(); return code === null ? null : refusalMessage(code); });

  reasonText = disabledMessage;
  kindText = transcriptKindMessage;

  constructor() {
    // A committed answer is recognized by a newer revision with no refusal and nothing in flight; only then is the sent draft cleared.
    effect(() => {
      const revision = this.view()?.stateRevision ?? -1;
      const problem = this.problem();
      const busy = this.sending();
      untracked(() => {
        const sent = this.submitted;
        if (sent === null || busy || problem !== null || revision <= sent.revision) return;
        this.submitted = null;
        this.reviewing.set(null);
        const kept = this.stored();
        if (sent.choice.kind === 'speak' && kept !== null && kept.text === sent.choice.text) this.stored.set({ ...kept, text: '' });
      });
    });
  }

  /** Format an exact server number with the compiled locale's digits. */
  n(value: number): string { return this.numbers.format(value); }
  /** A recipient as the selector shows it: its name and side, so who is being addressed is never only a color or position. */
  recipientText(fight: CombatEncounter, id: string): string {
    const side = fight.participants.find((who) => who.participantId === id)?.side;
    return side === undefined ? participantName(id) : `${participantName(id)} · ${sideMessage(side)}`;
  }
  label(choice: TacticalChoice): string { return choiceMessage(choice, this.fight()); }
  offerText(terms: string, recipient: string): string { return offerMessage(terms, participantName(recipient)); }
  replaceText(words: string): string { return replaceMessage(words); }
  wordsText(words: string): string { return wordsMessage(words); }
  speechCostText(recipient: string, left: number): string { return speechSpendMessage(participantName(recipient), this.n(left)); }
  spendText(points: number, after: number): string { return points === 0 ? priceMessage(this.n(0)) : spendMessage(this.n(points), this.n(after)); }
  checkText(check: { attribute: string; skill: string | null; rank: number }): string { return checkMessage(check.attribute, check.skill, this.n(check.rank)); }
  termsText(terms: { termsId: string; kind: string; cost: number }): string { return termsMessage(terms.termsId, terms.kind, this.n(terms.cost)); }

  /** Keep the draft's recipient and words together under the current encounter. */
  private keep(change: { recipient?: string; text?: string }): void {
    const fight = this.fight();
    if (fight === null) return;
    const now = this.draft();
    this.stored.set({ encounterId: fight.encounterId, recipient: change.recipient ?? now?.recipient ?? '', text: change.text ?? now?.text ?? '' });
  }

  /** Choose who the words are for; a changed recipient discards a review of the earlier one. */
  chooseRecipient(id: string): void { this.keep({ recipient: id }); this.reviewing.set(null); this.dismissed.emit(); }
  /** Store what the player typed; typing never reviews or sends. */
  write(words: string): void { this.keep({ text: words }); if (this.reviewing()?.choice.kind === 'speak') this.reviewing.set(null); }
  /** Empty the draft by the player's own action. */
  clear(): void { this.keep({ text: '' }); this.replacement.set(null); if (this.reviewing()?.choice.kind === 'speak') this.reviewing.set(null); }

  /** Put a suggestion in the draft; an existing draft is replaced only after the player agrees. */
  fill(words: string): void {
    if (this.text().trim() === '') { this.keep({ text: words }); this.replacement.set(null); } else this.replacement.set(words);
  }
  replaceDraft(): void { const words = this.replacement(); if (words !== null) this.keep({ text: words }); this.replacement.set(null); }
  keepDraft(): void { this.replacement.set(null); }

  /** Review one choice: this only previews its costs and sends nothing. */
  prepare(choice: TacticalChoice): void {
    const view = this.view();
    const fight = this.fight();
    if (view === null || fight === null || this.sending() || !resolveChoice(fight, choice).ok) return;
    this.dismissed.emit();
    this.reviewing.set({ choice, revision: view.stateRevision });
  }

  /** Back out of the review; the draft stays. */
  cancel(): void { this.dismissed.emit(); this.reviewing.set(null); }

  /** Hand the reviewed choice to the host; the only way this chat ever causes a send, and only from the confirm button. */
  confirm(): void {
    const mark = this.reviewing();
    if (this.sending() || mark === null || this.review() === null) return;
    this.submitted = mark;
    this.confirmed.emit(mark.choice);
  }
}
