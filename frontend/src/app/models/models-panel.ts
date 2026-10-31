/**
 * Settings → Models: the owner's four model slots with class badges, probes, same-class fallbacks
 * and the acknowledgement of a class change (P21.3-D).
 *
 * Every control is native (form fields, buttons), so the panel works by keyboard alone. Nothing here
 * handles a key or an endpoint: the API publishes and accepts identifiers only. A save that would
 * change a slot's billing or privacy class is held until the owner acknowledges the class the server
 * named; any later edit withdraws that pending acknowledgement.
 */
import { formatDate } from '@angular/common';
import {
  afterNextRender, ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, Injector, input, linkedSignal,
  LOCALE_ID, output, signal, viewChild,
} from '@angular/core';
import {
  CLASS_BADGES, ClassId, DECLARED_CLASS_BACKEND, Fallback, IMAGE_BACKENDS, MAX_FALLBACKS, ModelsClient, ModelsError, ModelsFailure,
  SLOT_IDS, SlotRequest, SlotStatus, TEXT_BACKENDS,
} from './models-client';
import {
  billingLabel, classLabel, modelsErrorMessage, modelsFailureMessage, privacyLabel, probeCodeMessage, probeStateLabel, slotLabel,
  sourceLabel,
} from './models-messages';

/** Editable copy of one slot's assignment; strings are exactly what the owner typed or chose. */
interface Draft {
  backend: string;
  model: string;
  effort: string;
  declared: ClassId | '';
  fallbacks: Fallback[];
}

/** A class change the server refused until acknowledged, with the exact request to repeat. */
interface Pending {
  readonly request: SlotRequest;
  readonly from: ClassId | null;
  readonly to: ClassId;
}

/** Message shown in the slot's live region. */
interface Notice {
  readonly error: boolean;
  readonly text: string;
}

/** @returns the value of a native input or select event target, or the empty string for any other target */
function valueOf(event: Event): string {
  const target = event.target;
  return target instanceof HTMLInputElement || target instanceof HTMLSelectElement ? target.value : '';
}

/** @returns the backends a slot may be assigned, in the order the panel offers them */
export function backendsFor(slot: SlotStatus['slot']): readonly string[] {
  return slot === 'image' ? IMAGE_BACKENDS : TEXT_BACKENDS;
}

/**
 * Build the editable copy of a stored assignment.
 *
 * An unassigned slot starts on the first backend its family offers with empty model and effort. The
 * class declaration of a custom endpoint is taken from the stored badge; an endpoint assigned at
 * startup has no known class, so it starts empty and must be chosen before saving.
 */
function draftFrom(slot: SlotStatus): Draft {
  const image = slot.slot === 'image';
  const backend = slot.backend ?? backendsFor(slot.slot)[0];
  const declared = backend === DECLARED_CLASS_BACKEND && slot.badge !== null && slot.badge.class !== 'subscription' ? slot.badge.class : '';
  return { backend, model: slot.model ?? '', effort: image ? '' : slot.effort ?? '', declared, fallbacks: slot.fallbacks.map(/** Copy so edits never touch the stored status. */ (entry) => ({ ...entry })) };
}

/** One slot: current assignment, badge, probe and the form to change them. */
@Component({
  selector: 'app-model-slot', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="model-slot" [attr.aria-labelledby]="prefix() + '-title'" [attr.data-slot]="status().slot">
    <h3 [id]="prefix() + '-title'">{{ label() }}</h3>
    <p class="muted" data-source>{{ source() }}</p>
    @if (status().backend !== null) {
      <dl class="model-facts">
        <dt i18n="@@models.field.backend">Backend</dt><dd><code>{{ status().backend }}</code></dd>
        <dt i18n="@@models.field.model">Model</dt><dd><code>{{ status().model }}</code></dd>
        @if (!image()) { <dt i18n="@@models.field.effort">Effort</dt><dd><code>{{ status().effort }}</code></dd> }
      </dl>
      <p data-class [attr.data-class-id]="status().badge?.class ?? 'unknown'"><strong i18n="@@models.field.class">Class</strong>: <span class="badge">{{ className() }}</span></p>
      @if (status().badge !== null) {
        <ul class="model-badge" data-badge><li>{{ billing() }}</li><li>{{ privacy() }}</li></ul>
      } @else { <p class="muted" i18n="@@models.class.unknownNote">The class of an assignment set at startup is not known until you save it here.</p> }
      @if (acknowledgement(); as text) { <p class="muted" data-acknowledged>{{ text }}</p> }
      <h4 i18n="@@models.fallbacks.title">Fallbacks</h4>
      @if (status().fallbacks.length === 0) { <p class="muted" i18n="@@models.fallbacks.none">No fallbacks are configured. A fallback is used only if you list it, and only within the same class.</p> }
      @else {
        <ol class="model-fallbacks" data-fallbacks>
          @for (entry of status().fallbacks; track $index) { <li><code>{{ entry.backend }}</code> · <code>{{ entry.model }}</code>@if (!image()) { · <code>{{ entry.effort }}</code> }</li> }
        </ol>
      }
      <h4 i18n="@@models.probe.title">Readiness</h4>
      <p data-probe [attr.data-probe-state]="status().probe.state"><strong>{{ probeState() }}</strong>@if (probeDetail(); as detail) { · {{ detail }} }</p>
      <p class="muted" i18n="@@models.probe.note">Ready means the backend lists this model. It does not show that the model answers well.</p>
      <button type="button" data-probe-button [attr.aria-disabled]="busy() === null ? null : 'true'" (click)="probe()" i18n="@@models.probe.button">Check readiness</button>
    }
    <form (submit)="save($event)" novalidate>
      <fieldset class="model-form">
        <legend i18n="@@models.form.legend">Change the assignment</legend>
        <label [for]="prefix() + '-backend'" i18n="@@models.field.backend">Backend</label>
        <select [id]="prefix() + '-backend'" (change)="setBackend($event)" [value]="draft().backend">
          @for (name of backends(); track name) { <option [value]="name" [selected]="name === draft().backend">{{ name }}</option> }
        </select>
        <label [for]="prefix() + '-model'" i18n="@@models.field.model">Model</label>
        <input [id]="prefix() + '-model'" type="text" maxlength="128" spellcheck="false" autocapitalize="off" autocomplete="off"
          [value]="draft().model" (input)="setModel($event)" [attr.aria-describedby]="prefix() + '-model-help'">
        <p class="muted" [id]="prefix() + '-model-help'" i18n="@@models.field.modelHelp">The model name exactly as the backend lists it.</p>
        @if (!image()) {
          <label [for]="prefix() + '-effort'" i18n="@@models.field.effort">Effort</label>
          <input [id]="prefix() + '-effort'" type="text" maxlength="32" spellcheck="false" autocapitalize="off" autocomplete="off"
            [value]="draft().effort" (input)="setEffort($event)" [attr.aria-describedby]="prefix() + '-effort-help'">
          <p class="muted" [id]="prefix() + '-effort-help'" i18n="@@models.field.effortHelp">Lowercase letters only, as the backend names it.</p>
        }
        @if (draft().backend === declaredBackend) {
          <label [for]="prefix() + '-declared'" i18n="@@models.field.declared">Class of this endpoint</label>
          <select [id]="prefix() + '-declared'" (change)="setDeclared($event)" [value]="draft().declared" [attr.aria-describedby]="prefix() + '-declared-help'">
            <option value="" [selected]="draft().declared === ''" i18n="@@models.field.declaredChoose">Choose a class</option>
            <option value="local" [selected]="draft().declared === 'local'">{{ localName }}</option>
            <option value="metered" [selected]="draft().declared === 'metered'">{{ meteredName }}</option>
          </select>
          <p class="muted" [id]="prefix() + '-declared-help'" i18n="@@models.field.declaredHelp">You say whether this endpoint stays on your machine or bills per use. The engine cannot check it.</p>
        }
        <h4 i18n="@@models.fallbacks.edit">Fallbacks in this class</h4>
        @for (entry of draft().fallbacks; track $index) {
          <fieldset class="model-fallback" [attr.data-fallback]="$index">
            <legend>{{ fallbackName($index) }}</legend>
            <label [for]="prefix() + '-fb-' + $index + '-backend'" i18n="@@models.field.backend">Backend</label>
            <select [id]="prefix() + '-fb-' + $index + '-backend'" (change)="setFallback($index, 'backend', $event)" [value]="entry.backend">
              @for (name of backends(); track name) { <option [value]="name" [selected]="name === entry.backend">{{ name }}</option> }
            </select>
            <label [for]="prefix() + '-fb-' + $index + '-model'" i18n="@@models.field.model">Model</label>
            <input [id]="prefix() + '-fb-' + $index + '-model'" type="text" maxlength="128" spellcheck="false" autocapitalize="off" autocomplete="off"
              [value]="entry.model" (input)="setFallback($index, 'model', $event)">
            @if (!image()) {
              <label [for]="prefix() + '-fb-' + $index + '-effort'" i18n="@@models.field.effort">Effort</label>
              <input [id]="prefix() + '-fb-' + $index + '-effort'" type="text" maxlength="32" spellcheck="false" autocapitalize="off" autocomplete="off"
                [value]="entry.effort" (input)="setFallback($index, 'effort', $event)">
            }
            <button type="button" data-remove-fallback (click)="removeFallback($index)">{{ removeName($index) }}</button>
          </fieldset>
        }
        @if (draft().fallbacks.length < maxFallbacks) { <button type="button" data-add-fallback (click)="addFallback()" i18n="@@models.fallbacks.add">Add a fallback</button> }
        <button type="submit" data-save [attr.aria-disabled]="busy() === null ? null : 'true'" #saveButton i18n="@@models.form.save">Save assignment</button>
      </fieldset>
    </form>
    @if (pending(); as ack) {
      <div class="model-acknowledge" role="group" data-acknowledge [attr.aria-labelledby]="prefix() + '-ack'">
        <h4 [id]="prefix() + '-ack'" tabindex="-1" #acknowledgeTitle i18n="@@models.ack.title">Confirm the change of class</h4>
        <p>{{ acknowledgeText(ack) }}</p>
        <ul><li>{{ acknowledgeBilling(ack) }}</li><li>{{ acknowledgePrivacy(ack) }}</li></ul>
        <button type="button" data-confirm [attr.aria-disabled]="busy() === null ? null : 'true'" (click)="confirm()" i18n="@@models.ack.confirm">Acknowledge and save</button>
        <button type="button" data-cancel (click)="cancel()" i18n="@@models.ack.cancel">Cancel</button>
      </div>
    }
    <p role="status" data-notice [class.error]="notice()?.error">{{ notice()?.text }}</p>
  </section>`,
})
export class ModelSlotCard {
  /** The stored state of the slot; replaced whenever a save or a probe finishes. */
  readonly status = input.required<SlotStatus>();
  /** Emits the slot the server returned after a save or a probe. */
  readonly changed = output<SlotStatus>();

  readonly declaredBackend = DECLARED_CLASS_BACKEND;
  readonly maxFallbacks = MAX_FALLBACKS;
  readonly localName = classLabel('local');
  readonly meteredName = classLabel('metered');
  readonly prefix = computed(() => `model-${this.status().slot.replace('.', '-')}`);
  readonly image = computed(() => this.status().slot === 'image');
  readonly backends = computed(() => backendsFor(this.status().slot));
  readonly label = computed(() => slotLabel(this.status().slot));
  readonly source = computed(() => sourceLabel(this.status().source));
  readonly className = computed(() => classLabel(this.status().badge?.class ?? null));
  readonly billing = computed(() => this.status().badge ? billingLabel(this.status().badge!.billing) : '');
  readonly privacy = computed(() => this.status().badge ? privacyLabel(this.status().badge!.privacy) : '');
  readonly probeState = computed(() => probeStateLabel(this.status().probe.state));
  /** Reset the draft only when the stored assignment changes, so a probe never discards unsaved edits. */
  readonly draft = linkedSignal<string, Draft>({
    source: () => JSON.stringify([this.status().slot, this.status().backend, this.status().model, this.status().effort, this.status().badge?.class, this.status().fallbacks]),
    computation: () => draftFrom(this.status()),
  });
  readonly pending = signal<Pending | null>(null);
  readonly notice = signal<Notice | null>(null);
  readonly busy = signal<'save' | 'probe' | null>(null);

  private readonly client = inject(ModelsClient);
  private readonly locale = inject(LOCALE_ID);
  private readonly injector = inject(Injector);
  private readonly saveButton = viewChild<ElementRef<HTMLButtonElement>>('saveButton');
  private readonly acknowledgeTitle = viewChild<ElementRef<HTMLElement>>('acknowledgeTitle');
  private readonly abort = new AbortController();

  /** Abort requests still in flight when the screen is left. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Leaving the screen cancels pending requests. */ () => this.abort.abort());
  }

  /** @returns the sentence naming the acknowledged class and when, or null when none is stored */
  acknowledgement(): string | null {
    const stored = this.status().acknowledged;
    if (stored === null) return null;
    const name = classLabel(stored.class);
    const at = formatDate(stored.at, 'medium', this.locale);
    return $localize`:@@models.acknowledged:You acknowledged the class ${name}:class: on ${at}:at:.`;
  }

  /** @returns the last probe's reason and timing, or null before any probe */
  probeDetail(): string | null {
    const probe = this.status().probe;
    if (probe.state === 'not-run' || probe.at === null) return null;
    const when = formatDate(probe.at, 'medium', this.locale);
    const milliseconds = String(probe.elapsedMillis);
    const timing = $localize`:@@models.probe.timing:Checked ${when}:when: in ${milliseconds}:milliseconds: ms.`;
    return probe.code === null ? timing : `${probeCodeMessage(probe.code)} ${timing}`;
  }

  /** @returns the heading of the nth fallback row (counting from one) */
  fallbackName(index: number): string {
    const number = String(index + 1);
    return $localize`:@@models.fallbacks.item:Fallback ${number}:number:`;
  }

  /** @returns the accessible name of the button that removes the nth fallback row */
  removeName(index: number): string {
    const number = String(index + 1);
    return $localize`:@@models.fallbacks.remove:Remove fallback ${number}:number:`;
  }

  /** @returns the sentence naming both classes of a pending change */
  acknowledgeText(ack: Pending): string {
    const from = classLabel(ack.from);
    const to = classLabel(ack.to);
    return $localize`:@@models.ack.change:This slot would change from the class ${from}:from: to the class ${to}:to:.`;
  }

  /** @returns what the new class costs, taken from the class the server named */
  acknowledgeBilling(ack: Pending): string {
    return billingLabel(CLASS_BADGES[ack.to].billing);
  }

  /** @returns who receives prompts under the new class */
  acknowledgePrivacy(ack: Pending): string {
    return privacyLabel(CLASS_BADGES[ack.to].privacy);
  }

  /** Withdraw any pending acknowledgement and stale notice, then apply the edit. */
  private edit(change: (draft: Draft) => Draft): void {
    this.pending.set(null);
    this.notice.set(null);
    this.draft.update(change);
  }

  /** Choose the backend; the class declaration belongs to the custom endpoint only. */
  setBackend(event: Event): void {
    const backend = valueOf(event);
    this.edit(/** The declaration is meaningless for any other backend. */ (draft) => ({ ...draft, backend, declared: backend === DECLARED_CLASS_BACKEND ? draft.declared : '' }));
  }

  /** Edit the model identifier. */
  setModel(event: Event): void {
    const model = valueOf(event);
    this.edit(/** Replace one field. */ (draft) => ({ ...draft, model }));
  }

  /** Edit the effort. */
  setEffort(event: Event): void {
    const effort = valueOf(event);
    this.edit(/** Replace one field. */ (draft) => ({ ...draft, effort }));
  }

  /** Choose the declared class of a custom endpoint; anything but the two allowed values clears it. */
  setDeclared(event: Event): void {
    const value = valueOf(event);
    const declared: Draft['declared'] = value === 'local' || value === 'metered' ? value : '';
    this.edit(/** Replace one field. */ (draft) => ({ ...draft, declared }));
  }

  /** Edit one field of one fallback row. */
  setFallback(index: number, field: keyof Fallback, event: Event): void {
    const value = valueOf(event);
    this.edit(/** Replace one field of one row. */ (draft) => ({ ...draft, fallbacks: draft.fallbacks.map(/** Copy the edited row only. */ (entry, position) => position === index ? { ...entry, [field]: value } : entry) }));
  }

  /** Append an empty fallback row on the draft's backend, up to the server's limit. */
  addFallback(): void {
    this.edit(/** The new row starts on the first backend of the family. */ (draft) => draft.fallbacks.length >= MAX_FALLBACKS ? draft
      : { ...draft, fallbacks: [...draft.fallbacks, { backend: draft.backend, model: '', effort: this.image() ? 'none' : '' }] });
  }

  /** Remove one fallback row and keep keyboard focus in the form. */
  removeFallback(index: number): void {
    this.edit(/** Drop the row at the position. */ (draft) => ({ ...draft, fallbacks: draft.fallbacks.filter(/** Keep every other row. */ (_, position) => position !== index) }));
    this.saveButton()?.nativeElement.focus();
  }

  /** Submit the draft; incomplete drafts are explained without contacting the server. */
  async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() !== null) return;
    const request = this.request();
    if (request === null) {
      this.notice.set({ error: true, text: $localize`:@@models.form.incomplete:Fill in the model, the effort and every fallback. A custom endpoint also needs its class.` });
      return;
    }
    this.pending.set(null);
    await this.submit(request);
  }

  /** Repeat the held request with the class the server named acknowledged. */
  async confirm(): Promise<void> {
    const ack = this.pending();
    if (ack === null || this.busy() !== null) return;
    await this.submit({ ...ack.request, acknowledgeClass: ack.to });
  }

  /** Drop the held request; nothing was stored, and focus returns to the save button. */
  cancel(): void {
    this.pending.set(null);
    this.notice.set({ error: false, text: $localize`:@@models.ack.cancelled:The class change was cancelled. Nothing was saved.` });
    this.saveButton()?.nativeElement.focus();
  }

  /** Probe the stored assignment and show the stored result. */
  async probe(): Promise<void> {
    if (this.busy() !== null) return;
    this.busy.set('probe');
    this.notice.set({ error: false, text: $localize`:@@models.probe.running:Checking readiness…` });
    try {
      const outcome = await this.client.probe(this.status().slot, this.abort.signal);
      if (outcome.kind === 'probed') {
        this.changed.emit(outcome.slot);
        this.notice.set({ error: false, text: $localize`:@@models.probe.finished:The check finished.` });
      } else {
        this.notice.set({ error: true, text: modelsErrorMessage(outcome.code) });
      }
    } catch (failure) {
      this.notice.set({ error: true, text: modelsFailureMessage(this.kindOf(failure)) });
    } finally {
      this.busy.set(null);
    }
  }

  /** Build the request from the draft, or null when a required field is empty. */
  private request(): SlotRequest | null {
    const draft = this.draft();
    const image = this.image();
    const custom = draft.backend === DECLARED_CLASS_BACKEND;
    const rows = draft.fallbacks.map(/** Image rows carry no effort. */ (entry) => ({ backend: entry.backend, model: entry.model.trim(), effort: image ? 'none' : entry.effort.trim() }));
    if (draft.model.trim() === '' || (!image && draft.effort.trim() === '') || (custom && draft.declared === '')
      || rows.some(/** A blank row is incomplete rather than ignored. */ (entry) => entry.model === '' || entry.effort === '')) return null;
    return {
      slot: this.status().slot, backend: draft.backend, model: draft.model.trim(), effort: image ? null : draft.effort.trim(),
      declaredClass: custom && draft.declared !== '' ? draft.declared : null, fallbacks: rows, acknowledgeClass: null,
    };
  }

  /** Send a request and show its outcome: stored, held for acknowledgement, or refused. */
  private async submit(request: SlotRequest): Promise<void> {
    this.busy.set('save');
    this.notice.set({ error: false, text: $localize`:@@models.form.saving:Saving the assignment…` });
    try {
      const outcome = await this.client.save(request, this.abort.signal);
      if (outcome.kind === 'saved') {
        this.pending.set(null);
        this.changed.emit(outcome.slot);
        this.notice.set({ error: false, text: $localize`:@@models.form.saved:The assignment was saved.` });
      } else if (outcome.kind === 'acknowledge') {
        this.pending.set({ request, from: outcome.from, to: outcome.to });
        this.notice.set({ error: false, text: $localize`:@@models.ack.needed:This change needs your acknowledgement. Nothing was saved yet.` });
        // Focus the explanation, not a confirming button, so a repeated Enter cannot confirm by accident.
        afterNextRender(/** The block exists only after this render. */ () => this.acknowledgeTitle()?.nativeElement.focus(), { injector: this.injector });
      } else {
        this.pending.set(null);
        this.notice.set({ error: true, text: modelsErrorMessage(outcome.code) });
      }
    } catch (failure) {
      this.pending.set(null);
      this.notice.set({ error: true, text: modelsFailureMessage(this.kindOf(failure)) });
    } finally {
      this.busy.set(null);
    }
  }

  /** @returns the failure category of an error, `unavailable` for anything unexpected */
  private kindOf(failure: unknown): ModelsFailure {
    return failure instanceof ModelsError ? failure.kind : 'unavailable';
  }
}

/** The Models section of Settings: loads the four slots and lists one card each. */
@Component({
  selector: 'app-models-panel', imports: [ModelSlotCard], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 id="models-title" i18n="@@models.title">Models</h2>
    <section aria-labelledby="models-title" class="models" data-models>
      <p i18n="@@models.intro">Choose which model serves each task slot. This panel stores names only: it never shows or asks for a key or an address. Changing the class of a slot, which sets who receives your prompts and who bills you, needs your acknowledgement.</p>
      <p role="status" data-models-state>
        @if (phase() === 'loading') { <ng-container i18n="@@models.loading">Loading the model slots…</ng-container> }
        @else if (phase() === 'failed') { {{ failureText() }} }
      </p>
      @if (phase() === 'failed') { <button type="button" data-retry (click)="load()" i18n="@@models.retry">Try again</button> }
      @for (slot of slots(); track slot.slot) { <app-model-slot [status]="slot" (changed)="replace($event)" /> }
    </section>`,
})
export class ModelsPanel {
  readonly slots = signal<readonly SlotStatus[]>([]);
  readonly phase = signal<'loading' | 'ready' | 'failed'>('loading');
  readonly failure = signal<ModelsFailure>('unavailable');
  readonly failureText = computed(() => modelsFailureMessage(this.failure()));

  private readonly client = inject(ModelsClient);
  private readonly abort = new AbortController();

  /** Load the slots now and cancel the request if the screen is left first. */
  constructor() {
    inject(DestroyRef).onDestroy(/** Leaving Settings cancels the read. */ () => this.abort.abort());
    void this.load();
  }

  /** Read the four slots; a failure keeps the panel usable and offers a retry. */
  async load(): Promise<void> {
    this.phase.set('loading');
    try {
      this.slots.set(await this.client.status(this.abort.signal));
      this.phase.set('ready');
    } catch (failure) {
      if (this.abort.signal.aborted) return;
      this.failure.set(failure instanceof ModelsError ? failure.kind : 'unavailable');
      this.phase.set('failed');
    }
  }

  /** Replace one slot with the state the server returned after a save or a probe. */
  replace(next: SlotStatus): void {
    this.slots.update(/** Keep the canonical order. */ (current) => SLOT_IDS.flatMap((id) => {
      const found = id === next.slot ? next : current.find((slot) => slot.slot === id);
      return found ? [found] : [];
    }));
  }
}
