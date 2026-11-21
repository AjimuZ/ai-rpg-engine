/**
 * Read-only view of one persisted playthrough: its public starting situation (with explicit
 * source-language fallback disclosure), the resume card (P09.5, docs/08 section 10: location,
 * who the player knows to be there, resources, the next decision, saved actions still awaiting
 * narration with their receipts, and a pause notice when adventure content is missing), the
 * panels and journal (P10.5: clock, character, conditions, inventory, quests, discoveries, promises, events), the
 * operations seen on the resumable stream with localized statuses and receipts, the presentation
 * cues of the stream as text with opt-in line audio (P05.7-B.2.1), localized error
 * and reconnect notices, and the scoped persistent draft (docs/13 section 4, docs/29 sections 3
 * and 6).
 *
 * Since P17.3-B the screen also shows the published narration as an attributed story in the visual-novel or text-focused layout
 * ({@link CampaignStory}) with optional quick replies. A quick reply only fills the draft held by the shared
 * {@link StoryInputState}; the draft is never sent automatically. The only writes are the player's own words sent with the Send
 * button to the person who spoke last ({@link CampaignPlaythrough.sendReply}, P17.3-B), a named combat action the player reviewed
 * and confirmed in the combat panel ({@link CampaignPlaythrough.send}, P12.2-A) and the start of an offered fight confirmed in the
 * offers list ({@link CampaignPlaythrough.engage}, P13.2-B). The stream/status reads go through
 * {@link CampaignSession}, which persists the operation identities and cursor so the explicit
 * EN↔IT reload resumes without duplicating or dropping deliveries.
 */
import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, LOCALE_ID, signal, untracked } from '@angular/core';
import { PreviewState } from '../preview/preview-state';
import { StoryInputState } from '../preview/story-input-state';
import { CampaignProtocolError, CampaignView, CombatView, isPending, JournalEntry, OperationView, PlayPanels, ResumeCard } from './campaign-protocol';
import { CampaignPanels } from './campaign-panels';
import { ArtworkIndex } from './campaign-artwork';
import { CombatPanel } from './combat-panel';
import { CampaignCues } from './campaign-cues';
import { CampaignStory } from './campaign-story';
import { OfferedReply, offeredReplies, replyRecipient, speakCommand, storyEntries } from './campaign-story-model';
import {
  awaitingMessage, campaignErrorMessage, commandTypeLabel, decisionMessage, dependencyIssueMessage, fallbackNotice, languageName,
  locationMessage, narrationReasonMessage, operationStatusMessage, presentMessage, receiptLines, resourceMessages, streamResetMessage,
} from './campaign-messages';
import { CampaignScope, CampaignSession, CampaignTransport } from './campaign-session';
import { choiceKey, envelope, resolveChoice, TacticalChoice, TacticalCommand } from './combat-actions';
import { CombatDraftStore } from './combat-draft';
import { EntryChoice, entryKey, resolveEntry } from './combat-entry';
import { EncounterOffers } from './encounter-offers';
import { MusicPanel } from '../music/music-panel';
import { DEFAULT_MOOD_POLICY, moodOf } from '../music/music-mood';
import { NO_PACK_MUSIC, PackMusic } from '../music/music-pack';

/** Delay before the first retry after a failed read, doubled up to {@link MAX_BACKOFF_MS}. */
const BASE_BACKOFF_MS = 2_000;
const MAX_BACKOFF_MS = 30_000;
/** Minimum spacing between successful idle polls, protecting the server if a hold ends early. */
const IDLE_PAUSE_MS = 1_000;

/** Wait for `ms` or until aborted; never rejects so callers re-check the signal. */
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

/** Show a playthrough's authorized public projection and follow its resumable stream while mounted. */
@Component({
  selector: 'app-campaign-playthrough', changeDetection: ChangeDetectionStrategy.OnPush, imports: [CampaignPanels, CampaignCues, CampaignStory, CombatPanel, EncounterOffers, MusicPanel],
  template: `
    <section class="panel campaign-playthrough" [attr.data-campaign-id]="campaignId()" aria-labelledby="campaign-title">
      @if (view(); as current) {
        <h2 id="campaign-title">{{ current.title }}</h2>
        <app-campaign-story [entries]="story()" [sceneUrl]="sceneUrl()" [sceneLabel]="sceneLabel()" [artwork]="artwork()" />
        @if (current.startingSituation; as situation) {
          <section class="starting-situation" aria-labelledby="starting-title">
            <h3 id="starting-title" i18n="@@campaign.view.startingTitle">Starting situation</h3>
            <p class="passage" [attr.lang]="situation.locale">{{ situation.text }}</p>
            @if (situation.fallback) {
              <p class="notice" role="note" data-fallback>{{ fallbackText(situation.locale) }}
                <span class="muted" i18n="@@campaign.view.fallbackLanguage">Text language: {{ languageText(situation.locale) }}</span></p>
            }
          </section>
        } @else { <p class="muted" i18n="@@campaign.view.noSituation">No decision is waiting for you in this playthrough.</p> }
        @if (resume(); as card) {
          <section class="resume-card" aria-labelledby="resume-title" data-resume>
            <h3 id="resume-title" i18n="@@campaign.resume.title">Where you are</h3>
            @if (card.mutationPaused) {
              <div class="notice" role="alert" data-paused>
                <p i18n="@@campaign.resume.paused">This playthrough is paused: adventure content it depends on is missing or damaged. Nothing can change until the same content is restored from a backup or imported again.</p>
                <ul>
                  @for (issue of card.issues; track $index) { <li>{{ issueText(issue) }}</li> }
                </ul>
              </div>
            }
            <dl>
              <dt i18n="@@campaign.resume.location">Location</dt>
              <dd [attr.lang]="card.location?.nameLocale ?? null" data-location>{{ locationText(card) }}</dd>
              <dt i18n="@@campaign.resume.present">Known to be here</dt>
              <dd data-present>{{ presentText(card) }}</dd>
              <dt i18n="@@campaign.resume.resources">Resources</dt>
              <dd data-resources>{{ resourceText(card).join(' · ') }}</dd>
              <dt i18n="@@campaign.resume.encounter">Encounter</dt>
              @if (card.inFight) { <dd data-encounter i18n="@@campaign.resume.inFight">In progress</dd> }
              @else { <dd i18n="@@campaign.resume.noEncounter">None</dd> }
              <dt i18n="@@campaign.resume.decision">Next decision</dt>
              <dd data-decision>{{ decisionText(card.pendingDecision) }}</dd>
            </dl>
            @if (card.awaitingNarrationTotal > 0) {
              <h4 i18n="@@campaign.resume.awaiting">Saved actions awaiting narration</h4>
              <ul class="awaiting-list">
                @for (entry of card.awaitingNarration; track entry.operationId) {
                  <li [attr.data-awaiting-id]="entry.operationId">
                    <p><strong>{{ command(entry.commandType) }}</strong> · {{ awaitingText(entry) }}</p>
                    @if (lines(entry.receipt); as receipt) {
                      <ul class="receipt" [attr.aria-label]="receiptLabel">
                        @for (line of receipt; track $index) { <li>{{ line }}</li> }
                      </ul>
                    }
                  </li>
                }
              </ul>
            }
            <p class="muted" i18n="@@campaign.resume.readOnly">This summary is read from the last saved state; reading it does not advance time.</p>
            @if (!card.inFight && !card.mutationPaused) {
              <p><button type="button" data-look-for-fights [disabled]="lookingForFights() || sending()" (click)="lookForFights()"
                i18n="@@campaign.resume.lookForFights">Look for fights you can start here</button></p>
            }
          </section>
        }
        @defer (when combat()?.encounter != null) {
          <app-combat-panel [view]="combat()" [journal]="panels()?.journal ?? null" [journalLocale]="panels()?.locale ?? null" [scope]="scope()" [sending]="sending()" [problem]="problem()" (confirmed)="send($event)" (dismissed)="problem.set(null)" />
        }
        @if (combat()?.encounter === null) {
          <app-encounter-offers [view]="combat()" [sending]="sending()" [problem]="problem()" (confirmed)="engage($event)" (dismissed)="problem.set(null)" />
        }
        <app-campaign-panels [panels]="panels()" [artwork]="shownArtwork()" [older]="older()" [nextBefore]="cursor()" [loadingOlder]="loadingOlder()" (olderRequested)="loadOlder()" />
      } @else if (failure()) { <p class="notice" role="alert" data-error>{{ failure() }}</p>
      } @else { <p role="status" i18n="@@campaign.view.loading">Loading the playthrough…</p> }

      <app-campaign-cues [cues]="cues()" />

      <app-music [scope]="musicScope()" [mood]="mood()" [tracks]="packMusic()?.tracks ?? null" />

      <h3 i18n="@@campaign.view.operations">Actions</h3>
      <p role="status" aria-live="polite" data-connection>{{ connectionMessage() }}</p>
      @if (failure() && view()) { <p class="notice" role="alert" data-error>{{ failure() }}</p> }
      @if (notice()) { <p class="notice" role="status" data-reset>{{ notice() }}</p> }
      @if (operations().length === 0) { <p class="muted" i18n="@@campaign.view.noOperations">No actions have been recorded for this playthrough in this browser.</p> }
      <ol class="operation-list">
        @for (operation of operations(); track operation.operationId) {
          <li [attr.data-operation-id]="operation.operationId" [attr.data-status]="operation.status">
            <p><strong>{{ command(operation.commandType) }}</strong> · {{ statusText(operation.status) }}</p>
            @if (operation.reason) { <p class="muted">{{ narration(operation.reason) }}</p> }
            @if (lines(operation.receipt); as receipt) {
              @if (receipt.length > 0) {
                <ul class="receipt" [attr.aria-label]="receiptLabel">
                  @for (line of receipt; track $index) { <li>{{ line }}</li> }
                </ul>
              }
            }
          </li>
        }
      </ol>
      <button type="button" (click)="refresh()" [disabled]="busy()" i18n="@@campaign.view.refresh">Check for updates</button>

      <div class="composer">
        @if (replies().length > 0) {
          <p id="campaign-suggestion-help" class="muted" i18n="@@campaign.reply.help">Suggestions fill an empty draft. Edit freely; nothing is sent until you press Send.</p>
          <ul class="quick-replies" aria-label="Suggestions" i18n-aria-label="@@story.suggestionsLabel">
            @for (reply of replies(); track reply.id) {
              <li><button type="button" data-suggestion [disabled]="!canChoose()" aria-describedby="campaign-suggestion-help"
                (click)="chooseReply(reply, field)">{{ replyLabel(reply) }}: {{ reply.label }}</button>
                <button type="button" data-dismiss [attr.aria-label]="dismissLabel(reply.label)"
                  (click)="dismissReply(reply.id, field)" i18n="@@story.dismiss">Dismiss</button></li>
            }
          </ul>
        }
        <label for="campaign-input" i18n="@@campaign.view.inputLabel">What do you say or do?</label>
        <textarea #field id="campaign-input" [value]="input.draft().text" maxlength="4000" rows="3"
          placeholder="Write a draft…" i18n-placeholder="@@story.draftPlaceholder" aria-describedby="campaign-draft-status campaign-composer-help"
          (input)="save(field)" (select)="save(field)" (keyup)="save(field)" (click)="save(field)" (focus)="restore(field)"></textarea>
        <div class="composer-actions"><button type="button" data-send [disabled]="!canSend()" (click)="sendReply()" i18n="@@campaign.view.send">Send</button>
          <button type="button" (click)="clear(field)" [disabled]="input.draft().text === ''" i18n="@@story.clear">Clear draft</button></div>
        <p id="campaign-draft-status" class="muted">{{ draftStatus() }}</p>
        @if (sendNote(); as note) { <p role="status" data-send-note>{{ note }}</p> }
        @if (recipient(); as to) {
          <p id="campaign-composer-help" class="muted" [attr.data-recipient]="to.actorId" i18n="@@campaign.reply.sendHelp">Your draft stays on this device. It is sent only when you press Send, as words to {{ to.name }}; if the reply is lost, pressing Send again is safe.</p>
        } @else {
          <p id="campaign-composer-help" class="muted" i18n="@@campaign.view.composerHelp">Your draft stays on this device and is never sent automatically, including after changing language. Sending actions is not connected yet.</p>
        }
      </div>
    </section>
  `,
})
export class CampaignPlaythrough {
  readonly adventureId = input.required<string>();
  readonly campaignId = input.required<string>();
  readonly input = inject(StoryInputState);
  private readonly transport = inject(CampaignTransport);
  private readonly session = inject(CampaignSession);
  private readonly drafts = inject(CombatDraftStore);
  private readonly locale = inject(LOCALE_ID);
  readonly receiptLabel = $localize`:@@campaign.view.receiptLabel:Result of the action`;
  readonly view = signal<CampaignView | null>(null);
  /** Resume card of the shown revision; null until read (or for a scope without one). */
  readonly resume = signal<ResumeCard | null>(null);
  /** Panels and journal of the shown revision (P10.5); null until read. */
  readonly panels = signal<PlayPanels | null>(null);
  /** Artwork in force for this playthrough (P17.1-B); null until read or when its read failed. */
  readonly artwork = signal<ArtworkIndex | null>(null);
  private readonly reading = inject(PreviewState);
  /**
   * The artwork the screen may draw: none in the text-focused layout, which promises no image element and no image request anywhere
   * on the screen (the gallery and the item icons included), so the panels then show their built-in text and icon fallbacks.
   */
  readonly shownArtwork = computed(() => this.reading.readingLayout() === 'text-focused' ? null : this.artwork());
  /** Combat projection of the shown revision (P12.1-B); read only while the resume card reports a fight, null otherwise. */
  readonly combat = signal<CombatView | null>(null);
  /** Older journal pages fetched on request, newest first; cleared whenever the first page is re-read. */
  readonly older = signal<readonly JournalEntry[]>([]);
  /** Cursor of the next older journal page, or null at the oldest event. */
  readonly cursor = signal<number | null>(null);
  readonly loadingOlder = signal(false);
  /** True while the player's own request for the fights offered here is being read (P13.2-B). */
  readonly lookingForFights = signal(false);
  /** True while one confirmed tactical action is on its way; a second confirmation is refused meanwhile. */
  readonly sending = signal(false);
  /** Stable code of the last refused or lost tactical submission, or null. */
  readonly problem = signal<string | null>(null);
  /** Stable code of the last refused or lost reply to the story (P17.3-B), or null; kept apart from the combat problem line. */
  readonly speech = signal<string | null>(null);
  /** Quick replies the player dismissed; their identities belong to one narration, so the list never needs resetting. */
  private readonly dismissed = signal<readonly string[]>([]);
  /** Attributed passages of the published narration of this playthrough, oldest first. */
  readonly story = computed(() => storyEntries(this.operations()));
  /** True while the playthrough is paused or a fight is on: replies are then not offered and nothing is sent from the story composer. */
  private readonly locked = computed(() => this.resume()?.mutationPaused === true || this.combat()?.encounter != null);
  /** Who the player's words go to (the speaker of the newest passage), or null when none is named or the composer is locked. */
  readonly recipient = computed(() => this.locked() ? null : replyRecipient(this.story()));
  /** Current quick replies, never more than three, without those the player dismissed. */
  readonly replies = computed((): OfferedReply[] => this.locked() ? []
    : offeredReplies(this.operations(), this.view()?.stateRevision ?? null).filter((reply) => !this.dismissed().includes(reply.id)));
  /** A chip is usable only for an empty draft, so a second choice never replaces the player's words. */
  readonly canChoose = computed(() => this.input.draft().text === '' && !this.sending());
  /** Send needs a named recipient, words, and no submission in flight. */
  readonly canSend = computed(() => this.recipient() !== null && this.input.draft().text.trim() !== '' && !this.sending() && this.view() !== null);
  /** The stable scene reference: the newest scene cue, else the place the resume card names. */
  private readonly sceneId = computed(() => this.cues().filter((cue) => cue.kind === 'SCENE' && cue.sceneId !== null).at(-1)?.sceneId ?? this.resume()?.location?.id ?? null);
  /** Hash URL of the scene image in force, or null when that scene has none. */
  readonly sceneUrl = computed(() => { const id = this.sceneId(); return id === null ? null : this.artwork()?.imageUrl('scene', id) ?? null; });
  /** Public name of the place, falling back to the scene reference. */
  readonly sceneLabel = computed(() => { const card = this.resume(); return card?.location ? locationMessage(card) : this.sceneId() ?? ''; });
  /** Localized line about the reply in flight, refused or lost; empty when there is nothing to say. */
  readonly sendNote = computed(() => {
    if (this.sending()) return $localize`:@@campaign.reply.sending:Sending your words…`;
    const code = this.speech();
    if (code === null) return '';
    if (code === 'LOST') return $localize`:@@campaign.reply.lost:The reply was lost, so your words may or may not have arrived. Press Send again: the same request is repeated and nothing is counted twice.`;
    return campaignErrorMessage(code);
  });
  /** The identity of the submission in flight or lost, kept so repeating the same choice at the same revision is idempotent. */
  private attempt: { readonly key: string; readonly revision: number; readonly requestId: string } | null = null;
  readonly failure = signal('');
  readonly notice = signal('');
  readonly busy = signal(false);
  readonly state = signal<'connecting' | 'live' | 'disconnected'>('connecting');
  readonly scope = computed((): CampaignScope => ({ adventureId: this.adventureId(), campaignId: this.campaignId() }));
  /** Scope identity for optional music: an adventure or campaign change silences the previous scope's track (P17.2-B). */
  readonly musicScope = computed(() => JSON.stringify([this.adventureId(), this.campaignId()]));
  /**
   * The pinned pack's mood policy and own tracks (P17.2-D): null while the listing is being read (the player then chooses
   * nothing for this scope), and no policy and no track when the listing fails or is invalid.
   */
  readonly packMusic = signal<PackMusic | null>(null);
  /** False from a (re)start until the combat projection of the read revision is known, so a re-read never flickers the mood. */
  private readonly moodKnown = signal(false);
  /**
   * Music mood of the public combat projection only, under the pack's policy or the default one; hidden state cannot reach it.
   * Null while the view is being re-read: the panel then keeps the current track instead of cueing the momentary `quiet` state
   * (found on the packaged installation, where the re-read after starting a fight restarted the tension track).
   */
  readonly mood = computed(() => this.moodKnown() ? moodOf(this.combat(), this.packMusic()?.policy ?? DEFAULT_MOOD_POLICY) : null);
  readonly operations = computed(() => this.session.operations(this.scope()));
  /** Presentation cues received on this page load, in display order; none is ever required to read the result. */
  readonly cues = computed(() => this.session.cues(this.scope()));
  readonly connectionMessage = computed(() => {
    switch (this.state()) {
      case 'live': return this.operations().some((o) => isPending(o.status))
        ? $localize`:@@campaign.view.live:Connected. Waiting for pending actions to finish.`
        : $localize`:@@campaign.view.liveIdle:Connected. All known actions are finished.`;
      case 'disconnected': return $localize`:@@campaign.view.disconnected:Connection unavailable. Your last known state and draft remain available; retrying automatically.`;
      default: return $localize`:@@campaign.view.connecting:Checking the playthrough…`;
    }
  });
  readonly draftStatus = computed(() => this.input.persistence() === 'saved'
    ? $localize`:@@story.saved:Draft saved on this device.`
    : this.input.persistence() === 'invalid' ? $localize`:@@story.invalid:Saved draft could not be restored. New edits will replace it.`
      : $localize`:@@story.unavailable:Draft kept in this tab only. Device storage is unavailable.`);
  private epoch = 0;
  private run: AbortController | undefined;
  private scopeKey = '';

  /** Restart the draft scope, state read and stream follower whenever the routed identity changes. */
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(/** Leaving detaches callbacks and aborts the long poll. */ () => { this.run?.abort(); this.input.deactivate(this.epoch); });
    effect(/** Route reuse must discard the previous scope's view and follower. */ () => {
      const scope = this.scope();
      untracked(/** Start work without subscribing to its own signals. */ () => this.start(scope));
    });
  }

  /** Activate the draft scope and begin loading; a previous run for another scope is aborted first. */
  private start(scope: CampaignScope): void {
    this.run?.abort();
    this.view.set(null);
    this.resume.set(null);
    this.panels.set(null);
    this.artwork.set(null);
    this.moodKnown.set(false);
    this.combat.set(null);
    this.older.set([]);
    this.cursor.set(null);
    this.failure.set('');
    this.notice.set('');
    if (this.scopeKey !== JSON.stringify(scope)) {
      this.scopeKey = JSON.stringify(scope);
      this.attempt = null;
      this.problem.set(null);
      this.speech.set(null);
      // The listing belongs to the scope's pinned pack revision: a refresh of the same scope keeps it, a new scope reads its own.
      this.packMusic.set(null);
    }
    this.state.set('connecting');
    this.epoch = this.input.activate(scope);
    const run = new AbortController();
    this.run = run;
    void this.follow(scope, run.signal, this.epoch);
  }

  /**
   * Load state, then reconcile and follow the stream with bounded exponential backoff until aborted.
   * The state is read again whenever an applied message or reset names a newer world revision.
   */
  private async follow(scope: CampaignScope, signal: AbortSignal, epoch: number): Promise<void> {
    let delay = BASE_BACKOFF_MS;
    let loaded = false;
    // Pending operations are re-queried on entry, after any failure and after a stream reset.
    let requery = true;
    while (!signal.aborted) {
      try {
        this.busy.set(true);
        if (!loaded) {
          // State and resume card describe the same scope; both are re-read after any newer revision.
          this.view.set(await this.transport.state(scope, signal));
          const card = await this.transport.resume(scope, signal);
          this.resume.set(card);
          const panels = await this.transport.panels(scope, signal);
          this.panels.set(panels);
          // The combat projection is read only during a fight, so a playthrough without one issues no extra request; outside a
          // fight its offers are read only when the player asks for them (lookForFights, P13.2-B).
          this.combat.set(card.inFight ? await this.transport.encounter(scope, signal) : null);
          this.moodKnown.set(true);
          // Artwork is decoration: a failed or invalid listing shows the built-in icons and never stops the playthrough.
          try { this.artwork.set(new ArtworkIndex(await this.transport.artwork(scope, signal), scope)); } catch { if (signal.aborted) return; this.artwork.set(null); }
          // The music policy belongs to the sealed pack revision the campaign pins, so one read per scope is enough. Music is
          // optional: a failed or invalid listing keeps the default policy and no pack track, and never stops the playthrough.
          if (this.packMusic() === null) {
            try { this.packMusic.set(await this.transport.music(scope, signal)); } catch { if (signal.aborted) return; this.packMusic.set(NO_PACK_MUSIC); }
          }
          this.older.set([]);
          this.cursor.set(panels.nextBefore);
          loaded = true;
        }
        if (requery) { await this.session.requery(scope, signal); requery = false; }
        // Narration published since the last pass (or lost to a reload) is read by identity before the next hold.
        await this.session.readNarration(scope, signal);
        const result = await this.session.poll(scope, signal, () => {
          // The first chunk of a response proves a live connection even before any message exists.
          this.failure.set('');
          this.state.set('live');
          this.busy.set(false);
          this.input.setStatus('ready', epoch);
          delay = BASE_BACKOFF_MS;
        });
        if (signal.aborted) return;
        if (result.reset) { this.notice.set(streamResetMessage(result.reset)); requery = true; }
        // A commit or reset makes the shown projection stale (for example a starting decision the
        // move already answered); re-read it before the next poll instead of showing old state.
        if (result.reset || (result.stateRevision !== null && result.stateRevision > (this.view()?.stateRevision ?? -1))) loaded = false;
        // An immediately ended poll (nothing new) must not spin; a held poll already waited server-side.
        if (result.applied === 0 && !result.reset) await pause(IDLE_PAUSE_MS, signal);
      } catch (error) {
        if (signal.aborted) return;
        const code = error instanceof CampaignProtocolError ? error.code : null;
        this.failure.set(campaignErrorMessage(code));
        this.state.set('disconnected');
        // A failed re-read keeps the last shown projection, so that case is a disconnection, not an error.
        this.input.setStatus(this.view() ? 'disconnected' : 'error', epoch);
        // Permanent answers (missing scope, denial, invalid payload) are not retried in a tight loop.
        if (error instanceof CampaignProtocolError && (error.kind === 'invalid' || error.status === 404 || error.status === 403)) {
          this.busy.set(false);
          return;
        }
        requery = true;
        await pause(delay, signal);
        delay = Math.min(delay * 2, MAX_BACKOFF_MS);
        this.input.setStatus('reconnecting', epoch);
      } finally { this.busy.set(false); }
    }
  }

  /**
   * Fetch the next older journal page and append it. A failure keeps what is shown and reports the usual
   * localized error; the cursor stays so the player can try again.
   */
  async loadOlder(): Promise<void> {
    const before = this.cursor();
    const run = this.run;
    if (before === null || run === undefined || this.loadingOlder()) return;
    this.loadingOlder.set(true);
    try {
      const page = await this.transport.panels(this.scope(), run.signal, before);
      if (run.signal.aborted) return;
      this.older.update((entries) => [...entries, ...page.journal]);
      this.cursor.set(page.nextBefore);
    } catch (error) {
      if (run.signal.aborted) return;
      this.failure.set(campaignErrorMessage(error instanceof CampaignProtocolError ? error.code : null));
    } finally { this.loadingOlder.set(false); }
  }

  /**
   * Read the fights the player may start here (P13.2-B), only because the player asked. This is the combat projection read: it
   * writes nothing and starts nothing. A projection that shows a fight, or a revision other than the shown resume card's, means the
   * playthrough moved on, so the whole view is re-read instead of mixing revisions. A failure keeps what is shown and reports the
   * usual localized error.
   */
  async lookForFights(): Promise<void> {
    const run = this.run;
    const card = this.resume();
    if (run === undefined || card === null || this.lookingForFights() || this.sending()) return;
    this.lookingForFights.set(true);
    try {
      const view = await this.transport.encounter(this.scope(), run.signal);
      if (run.signal.aborted) return;
      if (view.encounter !== null || view.stateRevision !== card.stateRevision) { this.refresh(); return; }
      this.combat.set(view);
    } catch (error) {
      if (run.signal.aborted) return;
      this.failure.set(campaignErrorMessage(error instanceof CampaignProtocolError ? error.code : null));
    } finally { this.lookingForFights.set(false); }
  }

  /**
   * Send one confirmed tactical action (P12.2-A).
   *
   * Runs only from the combat panel's confirm button. The choice is validated again against the newest projection; a choice that is
   * out of turn, unlisted, disabled or unaffordable is refused here and no request is made. A refusal by the server changes nothing
   * and is shown as a sentence; a lost reply keeps the request identity, so repeating the same choice at the same revision returns
   * the stored operation instead of playing it twice. After an answer the page re-reads the state and keeps following the stream.
   */
  async send(choice: TacticalChoice): Promise<void> {
    const view = this.combat();
    if (this.sending() || this.run === undefined || view === null) return;
    const resolved = resolveChoice(view.encounter, choice);
    if (!resolved.ok) { this.problem.set(resolved.refusal); return; }
    const scope = this.scope();
    const fight = view.encounter?.encounterId ?? null;
    // The re-read after an accepted speech can rebuild the chat before it sees the commit, so the device copy is emptied here first.
    const words = choice.kind === 'speak' ? choice.text : null;
    const accepted = words !== null && fight !== null
      ? () => { this.drafts.clearSent(scope.adventureId, scope.campaignId, fight, words); } : undefined;
    await this.dispatch(choiceKey(choice), resolved.command, view.stateRevision, { accepted });
  }

  /**
   * Send the player's draft as words to the person who spoke last (P17.3-B). Runs only from the Send button.
   *
   * The draft goes out exactly as written, never because a quick reply was chosen: a chip only fills the draft. Nothing is sent
   * during a fight (its own chat applies), while the playthrough is paused, without a named recipient or for an unsendable text.
   * A lost reply keeps the request identity and its revision, so pressing Send again with the same words returns the stored
   * operation instead of speaking twice. The draft is emptied once the server holds the operation (committed or still running),
   * never for a refusal, a clarification request or a lost reply.
   */
  async sendReply(): Promise<void> {
    const view = this.view();
    const to = this.recipient();
    const text = this.input.draft().text;
    if (this.sending() || this.run === undefined || view === null || to === null || this.locked()) return;
    const resolved = speakCommand(to.actorId, text);
    if (!resolved.ok) { this.speech.set(resolved.refusal); return; }
    const key = `speak:${to.actorId}:${text}`;
    // A lost attempt is repeated at the revision it was first sent at: the server matches the stored request by identity and body.
    const revision = this.attempt?.key === key ? this.attempt.revision : view.stateRevision;
    await this.dispatch(key, resolved.command, revision, {
      report: (code) => this.speech.set(code),
      answered: (operation) => { if (operation.committedRevision !== null || isPending(operation.status)) this.input.edit(''); },
    });
  }

  /** Fill an empty draft from a quick reply and move focus to it; a nonempty draft is never replaced and nothing is sent. */
  chooseReply(reply: OfferedReply, field: HTMLTextAreaElement): void {
    if (!this.canChoose()) return;
    this.input.edit(reply.draftText);
    field.value = this.input.draft().text;
    field.focus();
    field.setSelectionRange(this.input.draft().start, this.input.draft().end);
  }

  /** Hide one chip and keep keyboard position on the draft before its button disappears; the draft and the world are untouched. */
  dismissReply(id: string, field: HTMLTextAreaElement): void {
    field.focus();
    field.setSelectionRange(this.input.draft().start, this.input.draft().end);
    this.dismissed.update((ids) => [...ids, id]);
  }

  /**
   * Start one offered fight with `ENGAGE` (P13.2-B). Runs only from the confirm button of the offers list. The entry is validated
   * again against the newest projection: during a fight, for a template the projection no longer offers, or for an opening target
   * the offer does not list, nothing is sent. A lost reply keeps the request identity, so confirming the same entry at the same
   * revision again cannot start the fight twice.
   */
  async engage(choice: EntryChoice): Promise<void> {
    const view = this.combat();
    if (this.sending() || this.run === undefined || view === null) return;
    const resolved = resolveEntry(view, choice);
    if (!resolved.ok) { this.problem.set(resolved.refusal); return; }
    await this.dispatch(entryKey(choice), resolved.command, view.stateRevision);
  }

  /**
   * Submit one validated command under the identity rule: the same key at the same revision reuses the request identity of a lost
   * attempt; any other key or revision gets a new one. `accepted` runs once when the server answered with a committed operation (new
   * or replayed), before the re-read starts; it never runs for a refusal, an uncommitted operation or a lost reply. `answered` runs
   * for any operation the server returned. `report` receives the stable code of a refusal or `LOST`, and `null` when a new
   * submission starts; it defaults to the combat problem line.
   */
  private async dispatch(key: string, command: TacticalCommand, revision: number,
    hooks: { accepted?: () => void; answered?: (operation: OperationView) => void; report?: (code: string | null) => void } = {}): Promise<void> {
    const { accepted, answered } = hooks;
    const report = hooks.report ?? ((code: string | null) => this.problem.set(code));
    const run = this.run;
    if (run === undefined) return;
    if (this.attempt?.key !== key || this.attempt.revision !== revision) {
      this.attempt = { key, revision, requestId: globalThis.crypto.randomUUID() };
    }
    const scope = this.scope();
    this.sending.set(true);
    report(null);
    try {
      const operation = await this.transport.submit(scope, envelope(command, this.attempt.requestId, revision), run.signal);
      if (run.signal.aborted) return;
      this.attempt = null;
      this.session.applyOperation(scope, operation);
      answered?.(operation);
      if (operation.committedRevision !== null) accepted?.();
      this.refresh();
    } catch (error) {
      if (run.signal.aborted) return;
      if (error instanceof CampaignProtocolError && error.kind === 'api') {
        // A refusal is final for this identity: nothing was stored, so the next try is a new request.
        this.attempt = null;
        report(error.code ?? 'REFUSED');
        if (error.code === 'COMMAND_STALE_REVISION') this.refresh();
      } else {
        report('LOST');
      }
    } finally { this.sending.set(false); }
  }

  /** Check once for updates now; the continuous follower resumes by itself. */
  refresh(): void { this.start(this.scope()); }

  /** Localized text helpers for the template; each returns display text only. */
  /** Localized kind label of a quick reply, so its kind never relies on colour. */
  replyLabel(reply: OfferedReply): string {
    return reply.kind === 'question' ? $localize`:@@story.question:Question` : reply.kind === 'speech' ? $localize`:@@story.speech:Speech` : $localize`:@@story.action:Action`;
  }

  /** Accessible name of a chip's dismiss button; the reply text is interpolated literally. */
  dismissLabel(reply: string): string {
    return $localize`:@@story.dismissLabel:Dismiss suggestion: ${reply}:reply:`;
  }

  command = commandTypeLabel;
  statusText = operationStatusMessage;
  narration = narrationReasonMessage;
  fallbackText = fallbackNotice;
  languageText = languageName;
  decisionText = decisionMessage;
  awaitingText = awaitingMessage;
  issueText = dependencyIssueMessage;
  locationText = locationMessage;
  presentText = presentMessage;

  /** Localized resource lines of the resume card, formatted for the compiled locale. */
  resourceText(card: ResumeCard): string[] { return resourceMessages(card, this.locale); }

  /** Localized receipt sentences, or null when the operation has no committed receipt. */
  lines(receipt: Parameters<typeof receiptLines>[0] | null | undefined): string[] | null {
    return receipt ? receiptLines(receipt, this.locale) : null;
  }

  /** Save literal draft text and native selection; storage failures remain visible without blocking editing. */
  save(field: HTMLTextAreaElement): void { this.input.edit(field.value, field.selectionStart, field.selectionEnd); }

  /** Restore the saved selection on keyboard focus without moving focus on reload. */
  restore(field: HTMLTextAreaElement): void { field.setSelectionRange(this.input.draft().start, this.input.draft().end); }

  /** Clear only this scope's unsent draft; nothing is submitted. */
  clear(field: HTMLTextAreaElement): void {
    this.input.edit('');
    field.value = '';
    field.focus();
  }
}
