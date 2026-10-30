/**
 * Client state for validated presentation cues (P05.7-B.2.1): bounded, ordered by ordinal inside each
 * operation and free of repeated deliveries. Holds only what {@link PresentationCue} already carries;
 * nothing here is persisted, fetched or played.
 */
import { PresentationCue } from './campaign-protocol';

/** Most cues kept per scope; the oldest operation's lowest ordinal is dropped first. */
export const MAX_CUES = 32;
/** Most delivery identities remembered for duplicate detection, oldest forgotten first. */
export const MAX_SEEN_DELIVERIES = 256;

/** One kept cue with the operation that announced it. */
interface Entry { readonly operationId: string; readonly cue: PresentationCue }

/**
 * Ordered, deduplicated cues of one campaign scope.
 *
 * Invariants: no two kept cues share a `deliveryId`; cues of one operation are in ascending ordinal
 * order; operations appear in the order their first cue arrived; at most {@link MAX_CUES} are kept.
 */
export class CueState {
  private readonly seen = new Set<string>();
  private entries: readonly Entry[] = [];

  /**
   * Admit one cue.
   *
   * @returns true when the cue was kept; false when its `deliveryId` was already delivered, which
   *   includes a cue dropped by the size bound earlier (a late repeat is never shown as new)
   */
  add(operationId: string, cue: PresentationCue): boolean {
    if (this.seen.has(cue.deliveryId)) return false;
    this.seen.add(cue.deliveryId);
    if (this.seen.size > MAX_SEEN_DELIVERIES) this.seen.delete(this.seen.values().next().value!);

    const next = [...this.entries];
    let last = -1;
    let before = -1;
    next.forEach((entry, index) => {
      if (entry.operationId !== operationId) return;
      last = index;
      if (before < 0 && entry.cue.ordinal > cue.ordinal) before = index;
    });
    // A new operation goes last; otherwise the cue sorts by ordinal, and arrival order breaks ties.
    const position = last < 0 ? next.length : before >= 0 ? before : last + 1;
    next.splice(position, 0, { operationId, cue });
    while (next.length > MAX_CUES) next.shift();
    this.entries = next;
    return true;
  }

  /** Kept cues in display order; the returned array is a snapshot that later calls never mutate. */
  cues(): readonly PresentationCue[] {
    return this.entries.map(/** Cue values are immutable. */ (entry) => entry.cue);
  }
}

/**
 * Relative path of a registered asset (docs/13 section 3, `GET /api/v1/assets/{assetId}`).
 *
 * The id is a validated plain reference, so the result never carries a host, scheme or traversal.
 * The backend does not serve this route yet; callers must treat a failed load as "no audio".
 */
export function cueAssetPath(assetId: string): string {
  return `/api/v1/assets/${encodeURIComponent(assetId)}`;
}
