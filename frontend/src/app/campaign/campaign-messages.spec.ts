/** Localized error/receipt wording: closed code allowlists, generic fallback and locale-neutral mechanics. */
import { describe, expect, it } from 'vitest';
import { OPERATION_STATUSES, parseReceipt } from './campaign-protocol';
import { campaignErrorMessage, commandTypeLabel, fallbackNotice, narrationReasonMessage, operationStatusMessage, receiptLines, streamResetMessage } from './campaign-messages';
import { receipt } from './fixtures';

/** Public codes every campaign controller can answer; the repository check keeps this list complete. */
const KNOWN = ['LOCAL_ADMISSION_DENIED', 'COMMAND_INPUT_MALFORMED', 'COMMAND_BODY_TOO_LARGE', 'COMMAND_SCOPE_UNAVAILABLE',
  'COMMAND_REQUEST_CONFLICT', 'COMMAND_STALE_REVISION', 'COMMAND_CAMPAIGN_BUSY', 'COMMAND_ADMISSION_LIMIT', 'COMMAND_INVALID',
  'COMMAND_MODE_ILLEGAL', 'COMMAND_DECISION_UNAVAILABLE', 'COMMAND_ACTOR_UNAVAILABLE', 'COMMAND_DESTINATION_UNAVAILABLE',
  'COMMAND_ABILITY_UNAVAILABLE', 'COMMAND_PREREQUISITE_UNMET', 'COMMAND_TARGET_ILLEGAL', 'COMMAND_RESOURCE_INSUFFICIENT',
  'COMMAND_CONTENT_UNAVAILABLE', 'COMMAND_EFFECT_UNSUPPORTED', 'COMMAND_ITEM_UNAVAILABLE', 'COMMAND_EQUIPMENT_ILLEGAL',
  'COMMAND_QUANTITY_ILLEGAL', 'COMMAND_TRADE_REFUSED', 'COMMAND_QUEST_UNAVAILABLE', 'COMMAND_TIME_ILLEGAL', 'COMMAND_REST_UNAVAILABLE', 'COMMAND_ENCOUNTER_UNAVAILABLE', 'COMMAND_STORAGE_UNAVAILABLE', 'CAMPAIGN_SCOPE_UNAVAILABLE',
  'CAMPAIGN_READ_INPUT_INVALID', 'CAMPAIGN_READ_STORAGE_UNAVAILABLE', 'CAMPAIGN_READ_CONTENT_UNAVAILABLE', 'STREAM_CAPACITY_EXCEEDED',
  'LOCAL_OWNER_UNCONFIGURED'];

describe('Campaign messages', /** Register localized wording checks. */ () => {
  it('gives every known code a specific sentence and unknown or hostile codes the generic one', /** Server text never becomes UI text. */ () => {
    const generic = campaignErrorMessage(null);
    for (const code of KNOWN) {
      const message = campaignErrorMessage(code);
      expect(message, code).not.toBe(generic);
      expect(message).not.toContain('COMMAND_');
    }
    for (const code of ['NOPE', '__proto__', 'constructor', 'toString', '<script>']) expect(campaignErrorMessage(code)).toBe(generic);
  });

  it('describes every status, narration reason, reset reason and command type', /** No enum value may render empty. */ () => {
    for (const status of OPERATION_STATUSES) expect(operationStatusMessage(status).length).toBeGreaterThan(3);
    for (const reason of ['NARRATION_UNAVAILABLE', 'NARRATION_CANCELLED', 'NARRATION_FAILED', 'GENERATION_RETIRED', 'NARRATION_INTERRUPTED']) {
      expect(narrationReasonMessage(reason)).toContain('saved result');
    }
    for (const reason of ['CURSOR_EXPIRED', 'CURSOR_UNKNOWN', 'GENERATION_CHANGED']) expect(streamResetMessage(reason).length).toBeGreaterThan(10);
    expect(commandTypeLabel('MOVE')).toBe('Move');
    // Item commands (P10.2) have their own plain labels; unknown types stay literal.
    expect(['USE_ITEM', 'EQUIP_ITEM', 'UNEQUIP_ITEM', 'TRANSFER_ITEM', 'TRADE_ITEMS'].map(commandTypeLabel))
      .toEqual(['Use an item', 'Equip an item', 'Take off an item', 'Give an item', 'Trade items']);
    // Quest commands (P10.3) have plain labels of their own.
    expect(['ACCEPT_QUEST', 'SUSPEND_QUEST', 'RESUME_QUEST'].map(commandTypeLabel))
      .toEqual(['Accept a quest', 'Set a quest aside', 'Resume a quest']);
    expect(commandTypeLabel('CUSTOM_THING')).toBe('CUSTOM_THING');
    expect(fallbackNotice('it')).toContain('Italian');
    expect(fallbackNotice('en')).toContain('English');
  });

  it('formats the same committed mechanics for each locale without changing the values', /** EN and IT receipts carry identical numbers. */ () => {
    const value = parseReceipt({ ...receipt(1234), resources: [{ resourceKey: 'vigor', before: 1234567, after: -8 }] });
    const en = receiptLines(value, 'en');
    const it = receiptLines(value, 'it');
    expect(en).toHaveLength(it.length);
    expect(en.join('|')).toContain('1,234,567');
    expect(it.join('|')).toContain('1.234.567');
    const digits = (lines: string[]) => lines.join(' ').replace(/[.,]/g, '').match(/-?\d+/g);
    expect(digits(en)).toEqual(digits(it));
    expect(en.some((line) => line.includes('vigor'))).toBe(true);
    expect(en.some((line) => line.includes('succeeded'))).toBe(true);
  });
});
