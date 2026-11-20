/**
 * Starting an offered fight (P13.2-B, ADR 0042): lists the entries of the encounters the combat projection offers outside a fight,
 * validates one entry against that projection and builds the closed `ENGAGE` command.
 *
 * Everything here is pure and total. The page may name only a template the server offered at the shown revision, and an opening
 * attack only on a target that offer lists; anything else, and anything during a fight, yields a refusal and no command, so nothing
 * leaves the page for it. The server repeats every entry check on its committed state.
 */
import { CombatView, EncounterOffer } from './campaign-protocol';
import { TacticalCommand } from './combat-actions';

/** One way to start an offered fight: plainly, or by attacking one of the offer's opening targets. */
export interface EntryChoice { readonly templateId: string; readonly openingStrike: string | null }

/** A listed entry with its stable key. */
export interface EntryEntry { readonly key: string; readonly choice: EntryChoice }

/** Result of validating one entry: the command with the offer it starts, or the refusal and nothing to send. */
export type EntryResolution =
  | { readonly ok: true; readonly command: TacticalCommand; readonly offer: EncounterOffer }
  | { readonly ok: false; readonly refusal: 'NOT_YOUR_TURN' | 'NOT_LISTED' };

/**
 * Stable identity of an entry. It never collides with a tactical choice's key, so a lost `ENGAGE` and a lost tactical action never
 * share a request identity.
 */
export function entryKey(choice: EntryChoice): string {
  return `engage:${choice.templateId}:${choice.openingStrike ?? ''}`;
}

/**
 * List every entry the projection offers, in offer order: for each offer the plain start first, then one entry per opening target.
 *
 * @returns an empty list without a projection or during a fight
 */
export function entryEntries(view: CombatView | null): EntryEntry[] {
  if (view === null || view.encounter !== null) return [];
  const entries: EntryEntry[] = [];
  for (const offer of view.offers) {
    for (const openingStrike of [null, ...offer.openingTargets]) {
      const choice = { templateId: offer.templateId, openingStrike };
      entries.push({ key: entryKey(choice), choice });
    }
  }
  return entries;
}

/**
 * Validate one entry against the projection and build its `ENGAGE` command.
 *
 * Contract: returns a command only when the projection shows no fight, offers the named template, and either names no opening attack
 * or names one of that offer's opening targets. During a fight the refusal is `NOT_YOUR_TURN`; any other mismatch is `NOT_LISTED`.
 * The payload holds exactly `encounterId` and, only with an opening attack, `openingStrike`.
 */
export function resolveEntry(view: CombatView | null, choice: EntryChoice): EntryResolution {
  if (view === null || view.encounter !== null) return { ok: false, refusal: 'NOT_YOUR_TURN' };
  const offer = view.offers.find((candidate) => candidate.templateId === choice.templateId);
  if (offer === undefined || (choice.openingStrike !== null && !offer.openingTargets.includes(choice.openingStrike))) {
    return { ok: false, refusal: 'NOT_LISTED' };
  }
  const payload = choice.openingStrike === null ? { encounterId: offer.templateId } : { encounterId: offer.templateId, openingStrike: choice.openingStrike };
  return { ok: true, command: { commandType: 'ENGAGE', payload }, offer };
}
