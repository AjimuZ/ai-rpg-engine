/**
 * Device-local store of the unsent combat chat draft (P12.5-B, docs/13 "P12.5-B"): keeps the recipient and words a player wrote
 * for one fight across a reload and a reconnect, the way the narrative draft is kept since P05.6.
 *
 * Contract: one record per playthrough, tied to the encounter it was written in, so a draft never reaches another fight and the
 * store never grows beyond one record per playthrough. It uses the same bounded storage port as the narrative draft and treats
 * every storage failure as "kept in memory only": reading never throws and a malformed or foreign record is ignored untouched
 * until the next write replaces it. Nothing here sends, validates against a projection or computes anything about the fight.
 */
import { inject, Injectable } from '@angular/core';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { MAX_SPEECH_TEXT } from './combat-actions';

/** The recipient (empty while nobody is chosen) and the unsent words of one fight's chat. */
export interface CombatDraft { readonly recipient: string; readonly text: string }

/** Bytes beyond which a stored record is refused unread. */
const MAX_RECORD = 4_000;

@Injectable({ providedIn: 'root' })
export class CombatDraftStore {
  private readonly storage = inject(STORY_DRAFT_STORAGE);

  /** Build the origin-local key of a playthrough; punctuation in identifiers cannot merge two playthroughs. */
  private key(adventureId: string, campaignId: string): string {
    return 'engine.combat-draft.v1:' + JSON.stringify([adventureId, campaignId]);
  }

  /**
   * Read the draft stored for this fight.
   *
   * @returns the draft, or null when none is stored, it belongs to another encounter, it is malformed or storage is unavailable
   */
  read(adventureId: string, campaignId: string, encounterId: string): CombatDraft | null {
    try {
      const raw = this.storage?.getItem(this.key(adventureId, campaignId)) ?? null;
      if (raw === null || raw.length > MAX_RECORD) return null;
      const value: unknown = JSON.parse(raw);
      if (typeof value !== 'object' || value === null) return null;
      const record = value as Record<string, unknown>;
      if (record['version'] !== 1 || record['encounterId'] !== encounterId || typeof record['recipient'] !== 'string' ||
        typeof record['text'] !== 'string' || record['recipient'].length > 200 || record['text'].length > MAX_SPEECH_TEXT) return null;
      return { recipient: record['recipient'], text: record['text'] };
    } catch { return null; }
  }

  /**
   * Replace the playthrough's stored draft with this fight's.
   *
   * @returns true when the bytes were stored; false when storage refused them, in which case the draft stays in the page only
   */
  write(adventureId: string, campaignId: string, encounterId: string, draft: CombatDraft): boolean {
    try {
      // A record the reader would refuse is not written, so a stored draft is always one that can come back.
      if (!this.storage || draft.text.length > MAX_SPEECH_TEXT || draft.recipient.length > 200) return false;
      this.storage.setItem(this.key(adventureId, campaignId), JSON.stringify({ version: 1, encounterId, recipient: draft.recipient, text: draft.text }));
      return true;
    } catch { return false; }
  }

  /**
   * Empty the stored words of this fight after the server accepted exactly these words, keeping the recipient (P12.5-A).
   *
   * The host calls this before it re-reads the playthrough, because that re-read can tear the chat down before the chat sees the
   * commit; without it the rebuilt chat would restore words that were already said. Words edited since the send differ from
   * `sent` and are left untouched, as is a record of another encounter.
   *
   * @returns true when the stored words were emptied; false when nothing matched or storage refused the write
   */
  clearSent(adventureId: string, campaignId: string, encounterId: string, sent: string): boolean {
    const kept = this.read(adventureId, campaignId, encounterId);
    if (kept === null || kept.text !== sent) return false;
    return this.write(adventureId, campaignId, encounterId, { recipient: kept.recipient, text: '' });
  }
}
