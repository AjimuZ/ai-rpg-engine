/** Own bounded public projection/draft state; this module performs no transport or canonical mutations. */
import { computed, Inject, Injectable, InjectionToken, signal } from '@angular/core';

/** Minimal persistence boundary; browser denial/quota errors are caught by the service. */
export interface DraftStorage {
  /** Read bytes for a scoped key; return null when absent and throw on storage denial. */
  getItem(key: string): string | null;
  /** Replace bytes for a scoped key; return nothing and throw on denial/quota exhaustion. */
  setItem(key: string, value: string): void;
}
/** Stable IDs separate adventure and campaign drafts; display names never form storage keys. */
export interface StoryScope { readonly adventureId: string; readonly campaignId: string }
/** Public plain-text suggestion; choosing it only populates the unsent composer. */
export interface QuickReply { readonly id: string; readonly kind: 'speech' | 'question' | 'action'; readonly text: string }
/** Canonical revision and independent presentation generation order public deliveries without advancing time. */
export interface StoryProjection extends StoryScope {
  readonly revision: number;
  readonly presentationRevision: number;
  readonly suggestions: readonly QuickReply[];
}
/** Unsent text and UTF-16 selection offsets; never a submitted command or operation identity. */
export interface InputDraft { readonly text: string; readonly start: number; readonly end: number }
/** Specific presentation/recovery states; transport and durable operation reconciliation belong to P06/P07. */
export type StoryStatus = 'loading' | 'preview' | 'ready' | 'disconnected' | 'reconnecting' |
  'error' | 'stale-revision' | 'clarification' | 'cancelled' | 'narration-pending' | 'restore-required';

/** Obtain same-origin browser storage; unavailable environments fall back to memory without throwing. */
export const STORY_DRAFT_STORAGE = new InjectionToken<DraftStorage | null>('Story draft storage', {
  providedIn: 'root', factory: /** Never expose browser policy errors to rendering. */ () => {
    try { return globalThis.localStorage ?? null; } catch { return null; }
  },
});

const emptyDraft: InputDraft = { text: '', start: 0, end: 0 };

/** Accept scoped public deliveries and local edits; malformed/stale input is rejected and storage faults stay visible. */
@Injectable({ providedIn: 'root' })
export class StoryInputState {
  private scope: StoryScope | undefined;
  private epoch = 0;
  private readonly memory = new Map<string, { draft: InputDraft; persistence: 'saved' | 'unavailable' | 'invalid' }>();
  private readonly snapshots = new Map<string, StoryProjection>();
  private readonly unresolved = new Set<string>();
  private readonly currentProjection = signal<StoryProjection | null>(null);
  readonly projection = this.currentProjection.asReadonly();
  private readonly currentDraft = signal<InputDraft>(emptyDraft);
  readonly draft = this.currentDraft.asReadonly();
  private readonly currentStatus = signal<StoryStatus>('loading');
  readonly status = this.currentStatus.asReadonly();
  private readonly saved = signal<'saved' | 'unavailable' | 'invalid'>('saved');
  readonly persistence = this.saved.asReadonly();
  private readonly dismissed = signal<readonly string[]>([]);
  readonly suggestions = computed(/** Hide locally dismissed chips; duplicate delivery cannot resurrect them. */ () =>
    (this.projection()?.suggestions ?? []).filter(/** Match stable public IDs within this delivery. */ (reply) => !this.dismissed().includes(reply.id)));

  /** Receive an injectable storage port; null keeps edits in memory and reports persistence unavailable. */
  constructor(@Inject(STORY_DRAFT_STORAGE) private readonly storage: DraftStorage | null) {}

  /** Verify every edited preview draft before reload; unresolved operations or storage loss block navigation. */
  prepareReload(): boolean {
    if (this.unresolved.size > 0 || !['loading', 'preview', 'ready', 'disconnected', 'error', 'stale-revision'].includes(this.status()) ||
      (this.scope && this.scope.campaignId !== 'local-preview')) return false;
    try {
      // Re-read exact bytes: a later quota/policy failure or another tab's edit must not silently lose this tab's draft.
      for (const [key, value] of this.memory) {
        if (value.persistence !== 'saved' || !this.storage ||
          this.storage.getItem(key) !== JSON.stringify({ version: 1, ...value.draft })) return false;
      }
      return true;
    } catch { return false; }
  }

  /** Invalidate old-language callbacks and reply references after persistence succeeds; never submit or mutate a draft. */
  invalidateForReload(): void {
    this.epoch++;
    this.currentProjection.set(null);
    this.currentStatus.set('loading');
    this.snapshots.clear();
  }

  /** Activate stable IDs and return a callback epoch; leaving/re-entering a scope invalidates old asynchronous callbacks. */
  activate(scope: StoryScope): number {
    if (this.scope?.adventureId === scope.adventureId && this.scope.campaignId === scope.campaignId) return this.epoch;
    this.scope = { ...scope };
    // Real campaign identity requires future durable operation/cursor reconciliation, even after route detachment.
    if (scope.campaignId !== 'local-preview') this.unresolved.add(this.key());
    this.epoch++;
    const cachedProjection = this.snapshots.get(this.key());
    // Fresh reply references prevent detached UI closures from selecting a previously displayed chip.
    this.currentProjection.set(cachedProjection ? { ...cachedProjection,
      suggestions: cachedProjection.suggestions.map(/** Rebind replies to this screen activation. */ (reply) => ({ ...reply })) } : null);
    this.dismissed.set([]);
    this.currentStatus.set('loading');
    this.saved.set(this.storage ? 'saved' : 'unavailable');
    const cached = this.memory.get(this.key());
    this.currentDraft.set(cached?.draft ?? this.restore());
    if (cached) this.saved.set(cached.persistence);
    // A restored nonempty draft also belongs to this tab: later conflicting writes must block its language reload.
    if (!cached && this.persistence() === 'saved' && this.draft().text !== '') {
      this.memory.set(this.key(), { draft: this.draft(), persistence: 'saved' });
    }
    return this.epoch;
  }

  /** Detach the current screen by epoch; obsolete teardown cannot clear another screen's active context. */
  deactivate(epoch: number): void {
    if (epoch !== this.epoch) return;
    this.scope = undefined;
    this.epoch++;
    this.currentStatus.set('loading');
  }

  /** Accept a validated newer snapshot in the active scope; false leaves the last projection and draft intact. */
  accept(value: StoryProjection, epoch: number): boolean {
    if (epoch !== this.epoch || !this.scope || value.adventureId !== this.scope.adventureId ||
      value.campaignId !== this.scope.campaignId || !Number.isSafeInteger(value.revision) || value.revision < 0 ||
      !Number.isSafeInteger(value.presentationRevision) || value.presentationRevision < 0 ||
      !Array.isArray(value.suggestions) || value.suggestions.length > 3 ||
      value.suggestions.some(/** Refuse unbounded/nonliteral reply fields before any rendering. */ (reply) =>
        !reply || !['speech', 'question', 'action'].includes(reply.kind) || typeof reply.id !== 'string' || !reply.id.trim() || reply.id.length > 100 ||
        typeof reply.text !== 'string' || !reply.text.trim() || reply.text.length > 240) ||
      new Set(value.suggestions.map(/** IDs must be unique within a delivery. */ (reply) => reply.id)).size !== value.suggestions.length) return false;
    const previous = this.projection();
    if (previous && (value.revision < previous.revision ||
      (value.revision === previous.revision && value.presentationRevision <= previous.presentationRevision))) return false;
    // Copy incoming values so caller mutation cannot silently change an accepted signal snapshot.
    this.currentProjection.set({ ...value, suggestions: value.suggestions.map(/** Detach each reply from its producer. */ (reply) => ({ ...reply })) });
    this.snapshots.set(this.key(), this.projection()!);
    this.dismissed.set([]);
    if (this.status() === 'loading') this.currentStatus.set('ready');
    return true;
  }

  /** Persist a local bounded edit/selection; storage errors never discard the new in-memory text or replay it. */
  edit(text: string, start = text.length, end = start): void {
    if (!this.scope) return;
    const bounded = text.slice(0, 4000);
    const from = Number.isSafeInteger(start) ? Math.max(0, Math.min(start, bounded.length)) : bounded.length;
    const to = Number.isSafeInteger(end) ? Math.max(from, Math.min(end, bounded.length)) : from;
    const draft = { text: bounded, start: from, end: to };
    this.currentDraft.set(draft);
    try {
      if (!this.storage) throw new Error('Storage unavailable');
      this.storage.setItem(this.key(), JSON.stringify({ version: 1, ...draft }));
      this.saved.set('saved');
    } catch { this.saved.set('unavailable'); }
    this.memory.set(this.key(), { draft, persistence: this.persistence() });
  }

  /** Insert a current reply into an empty draft only; return false for stale, dismissed or recovery-state chips. */
  choose(source: QuickReply): boolean {
    if (this.draft().text !== '' || !['preview', 'ready'].includes(this.status())) return false;
    const reply = this.suggestions().find(/** Resolve only within the accepted projection. */ (entry) => entry === source);
    if (!reply) return false;
    this.edit(reply.text);
    return true;
  }

  /** Hide one current chip without touching text, reading position, projection revision or the world. */
  dismiss(id: string): void {
    if (this.suggestions().some(/** Unknown IDs cannot grow dismissal state. */ (entry) => entry.id === id)) this.dismissed.set([...this.dismissed(), id]);
  }

  /** Apply a scoped connection/operation presentation status; obsolete callbacks cannot affect the current screen. */
  setStatus(status: StoryStatus, epoch: number): void {
    if (this.scope && epoch === this.epoch) {
      this.currentStatus.set(status);
      // Keep recovery obligations across Settings navigation; detachment must not turn a pending operation into idle.
      if (this.scope.campaignId !== 'local-preview' || !['loading', 'preview', 'ready', 'disconnected', 'error', 'stale-revision'].includes(status)) {
        this.unresolved.add(this.key());
      } else this.unresolved.delete(this.key());
    }
  }

  /** Build an unambiguous versioned origin-local key; punctuation in IDs cannot merge campaigns. */
  private key(): string { return 'engine.story-draft.v1:' + JSON.stringify([this.scope!.adventureId, this.scope!.campaignId]); }

  /** Restore only the supported bounded schema; malformed bytes remain untouched and yield a visible empty fallback. */
  private restore(): InputDraft {
    try {
      if (!this.storage) throw new Error('Storage unavailable');
      const raw = this.storage.getItem(this.key());
      if (raw === null) return emptyDraft;
      if (raw.length > 25000) { this.saved.set('invalid'); return emptyDraft; }
      let value: unknown;
      try { value = JSON.parse(raw); } catch { this.saved.set('invalid'); return emptyDraft; }
      if (typeof value === 'object' && value !== null && 'version' in value && value.version === 1 &&
        'text' in value && typeof value.text === 'string' && value.text.length <= 4000 &&
        'start' in value && typeof value.start === 'number' && Number.isSafeInteger(value.start) && value.start >= 0 &&
        'end' in value && typeof value.end === 'number' && Number.isSafeInteger(value.end) && value.end >= value.start && value.end <= value.text.length) {
        return { text: value.text, start: value.start, end: value.end };
      }
      this.saved.set('invalid');
    } catch { this.saved.set('unavailable'); }
    return emptyDraft;
  }
}
