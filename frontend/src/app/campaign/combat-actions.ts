/**
 * Named tactical actions of the combat panel (P12.2-A, docs/13 "P12.2-A tactical actions"): lists the player's legal choices from the
 * server's projection, validates one choice against that projection, previews what it consequentially costs and builds the closed
 * command payload the server accepts.
 *
 * Everything here is pure and total: a choice that is not listed, is disabled, belongs to a turn that is not the player's or costs
 * more than remains yields a refusal and no command, so nothing leaves the page for it. The server still decides legality and every
 * effect; this module only keeps the page from sending what the projection already shows to be illegal, and never computes an outcome.
 */
import { CombatEncounter } from './campaign-protocol';

/** One action the player can name; payload members are exactly those of the matching command type (docs/07 P11.2-P11.4). */
export type TacticalChoice =
  | { readonly kind: 'move'; readonly zone: string }
  | { readonly kind: 'strike'; readonly attackId: string; readonly targetId: string }
  | { readonly kind: 'ability'; readonly abilityId: string; readonly targetIds: readonly string[] }
  | { readonly kind: 'interact'; readonly actionId: string; readonly targetId: string | null }
  | { readonly kind: 'guard' }
  | { readonly kind: 'end' }
  /** Speech-only words to one participant (P12.2-B): `recipientId` is empty while the player has chosen nobody. */
  | { readonly kind: 'speak'; readonly recipientId: string; readonly text: string }
  /** The player's explicit answer to the offer on the table; the offer's identity comes from the projection, never from the page. */
  | { readonly kind: 'answer'; readonly decision: 'accept' | 'decline' }
  /** The player's explicit confirmation of a decided fight (P12.5-B); the encounter's identity comes from the projection. */
  | { readonly kind: 'settle' }
  /** The player's explicit start of round one of a fight just entered (P14.1-A.2); the engine rolls and stores the order. */
  | { readonly kind: 'roll' };

/** Longest utterance the server accepts, in UTF-16 units (docs/07 P11.2). */
export const MAX_SPEECH_TEXT = 500;

/** Stable refusal codes of the page's own validation; they never carry server text. */
export const TACTICAL_REFUSALS = ['NOT_YOUR_TURN', 'NOT_LISTED', 'DISABLED', 'ACTION_POINTS', 'FOCUS', 'NO_RECIPIENT', 'NOT_ADDRESSABLE',
  'SPEECH_LIMIT', 'EMPTY_TEXT', 'TEXT_TOO_LONG', 'TEXT_INVALID'] as const;
export type TacticalRefusal = (typeof TACTICAL_REFUSALS)[number];

/** The server refuses U+0000 and unpaired surrogates, which no stored text can hold. */
const UNSTORABLE = /\u0000|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/;

/** What a choice costs and changes in the player's own turn, taken from the projection (never computed from rules). */
export interface TacticalPreview {
  readonly choice: TacticalChoice;
  /** Action points the action spends, or for ending the turn the points that are forfeited. */
  readonly points: number;
  readonly pointsAfter: number;
  readonly focus: number;
  readonly focusAfter: number;
  /** True when the action passes control to the other participants. */
  readonly endsTurn: boolean;
  readonly check: { readonly attribute: string; readonly skill: string | null; readonly rank: number } | null;
  readonly terms: { readonly termsId: string; readonly kind: string; readonly cost: number } | null;
  /** For speech: the words, their recipient and the speech allowance left afterwards; speech spends no action points. */
  readonly speech: { readonly recipientId: string; readonly text: string; readonly remainingAfter: number } | null;
  /** For an answer to an offer: the decision the player is about to make; accepting binds the player to `terms`. */
  readonly decision: 'accept' | 'decline' | null;
  /** True when the choice settles a decided fight: it spends nothing and ends the encounter for good. */
  readonly settles: boolean;
  /** True when the choice rolls initiative for a fight just entered: it spends nothing and opens round one. */
  readonly rolls: boolean;
}

/** The typed command a choice becomes. */
export interface TacticalCommand { readonly commandType: string; readonly payload: Readonly<Record<string, unknown>> }

/** A listed choice with its display parts; `disabled` is the server's stable reason, or null when it can be reviewed. */
export interface TacticalEntry { readonly key: string; readonly choice: TacticalChoice; readonly disabled: string | null }

/** Result of validating one choice: the command with its preview, or the refusal and nothing to send. */
export type TacticalResolution =
  | { readonly ok: true; readonly command: TacticalCommand; readonly preview: TacticalPreview }
  | { readonly ok: false; readonly refusal: TacticalRefusal };

/** Stable identity of a choice, used for list keys and to recognize the same choice after the projection is re-read. */
export function choiceKey(choice: TacticalChoice): string {
  switch (choice.kind) {
    case 'move': return `move:${choice.zone}`;
    case 'strike': return `strike:${choice.attackId}:${choice.targetId}`;
    case 'ability': return `ability:${choice.abilityId}:${choice.targetIds.join(',')}`;
    case 'interact': return `interact:${choice.actionId}:${choice.targetId ?? ''}`;
    case 'guard': return 'guard';
    case 'end': return 'end';
    case 'speak': return `speak:${choice.recipientId}:${choice.text}`;
    case 'answer': return `answer:${choice.decision}`;
    case 'settle': return 'settle';
    case 'roll': return 'roll';
  }
}

/**
 * True while an entered fight waits for the player's explicit initiative roll. The engine never rolls it by itself: the
 * fight stays at phase `ENTERING` and round 0 until `ROLL_INITIATIVE` is committed, so no other decision exists then.
 */
export function awaitsInitiative(fight: CombatEncounter | null): boolean {
  return fight !== null && fight.phase === 'ENTERING' && fight.round === 0 && fight.outcome === null;
}

/** True while the fight waits at its verdict for the player's explicit settlement; no tactical decision exists then. */
export function awaitsSettlement(fight: CombatEncounter | null): boolean {
  return fight !== null && fight.phase === 'OUTCOME_PENDING' && fight.outcome !== null;
}

/**
 * List every named action the projection offers at the player's decision, one entry per action and target.
 *
 * @returns an empty list outside the player's decision; disabled entries stay listed so their reason is visible
 */
export function tacticalEntries(fight: CombatEncounter): TacticalEntry[] {
  // A decided fight offers exactly one choice, the explicit settlement; nothing else is legal at its verdict.
  if (awaitsSettlement(fight)) return [{ key: choiceKey({ kind: 'settle' }), choice: { kind: 'settle' }, disabled: null }];
  // An entered fight offers exactly one choice too: the roll that opens round one.
  if (awaitsInitiative(fight)) return [{ key: choiceKey({ kind: 'roll' }), choice: { kind: 'roll' }, disabled: null }];
  const options = fight.options;
  if (!fight.playerDecision || options === null) return [];
  const entries: TacticalEntry[] = [];
  const add = (choice: TacticalChoice, disabled: string | null = null): number => entries.push({ key: choiceKey(choice), choice, disabled });
  for (const move of options.moves) add({ kind: 'move', zone: move.zone });
  for (const strike of options.strikes) {
    if (strike.targets.length === 0) continue;
    for (const target of strike.targets) add({ kind: 'strike', attackId: strike.attackId, targetId: target }, strike.disabled);
  }
  for (const ability of options.abilities) {
    if (ability.targets.length === 0) add({ kind: 'ability', abilityId: ability.abilityId, targetIds: [] }, ability.disabled);
    else for (const target of ability.targets) add({ kind: 'ability', abilityId: ability.abilityId, targetIds: [target] }, ability.disabled);
  }
  for (const action of options.actions) {
    if (action.targets.length === 0) add({ kind: 'interact', actionId: action.actionId, targetId: null });
    else for (const target of action.targets) add({ kind: 'interact', actionId: action.actionId, targetId: target });
  }
  if (options.offer !== null) {
    add({ kind: 'answer', decision: 'accept' }, options.offer.canAccept ? null : 'UNAVAILABLE');
    add({ kind: 'answer', decision: 'decline' });
  }
  if (options.guard !== null) add({ kind: 'guard' }, options.guard.available ? null : 'UNAVAILABLE');
  if (options.endActivation) add({ kind: 'end' });
  return entries;
}

/**
 * Validate one choice against the projection and build its command and preview.
 *
 * Contract: returns a command only when the fight is at the player's own decision, the choice appears in the projection's legal
 * candidates with the same target, the candidate is not disabled, and its action points and Focus fit what the projection shows
 * remains. Any other input returns a refusal and builds nothing, so the caller sends no request. The server repeats every check
 * against the committed state, so a projection that is already stale still cannot change anything illegally.
 */
export function resolveChoice(fight: CombatEncounter | null, choice: TacticalChoice): TacticalResolution {
  const refuse = (refusal: TacticalRefusal): TacticalResolution => ({ ok: false, refusal });
  if (choice.kind === 'settle') {
    // Settlement is the only choice at a verdict and is never listed anywhere else, so it is refused unless the fight is decided.
    if (fight === null || !awaitsSettlement(fight)) return refuse('NOT_LISTED');
    return {
      ok: true, command: { commandType: 'SETTLE_ENCOUNTER', payload: { encounterId: fight.encounterId } },
      preview: { choice, points: 0, pointsAfter: 0, focus: 0, focusAfter: fight.player.focus, endsTurn: false, check: null, terms: null, speech: null, decision: null, settles: true, rolls: false },
    };
  }
  if (choice.kind === 'roll') {
    // Rolling is listed only at the entered fight's first decision, so it is refused at any other phase and builds nothing.
    if (fight === null || !awaitsInitiative(fight)) return refuse('NOT_LISTED');
    return {
      ok: true, command: { commandType: 'ROLL_INITIATIVE', payload: {} },
      preview: { choice, points: 0, pointsAfter: 0, focus: 0, focusAfter: fight.player.focus, endsTurn: false, check: null, terms: null, speech: null, decision: null, settles: false, rolls: true },
    };
  }
  if (awaitsInitiative(fight)) return refuse('NOT_YOUR_TURN');
  if (awaitsSettlement(fight)) return refuse('NOT_YOUR_TURN');
  const options = fight?.options ?? null;
  if (fight === null || !fight.playerDecision || options === null || fight.actionPoints === null) return refuse('NOT_YOUR_TURN');
  const left = fight.actionPoints;
  const focusLeft = fight.player.focus;
  let points = 0;
  let focus = 0;
  let endsTurn = false;
  let check: TacticalPreview['check'] = null;
  let terms: TacticalPreview['terms'] = null;
  let speech: TacticalPreview['speech'] = null;
  let decision: TacticalPreview['decision'] = null;
  let command: TacticalCommand;
  switch (choice.kind) {
    case 'move': {
      const move = options.moves.find((entry) => entry.zone === choice.zone);
      if (!move) return refuse('NOT_LISTED');
      points = move.cost;
      command = { commandType: 'MOVE_ZONE', payload: { zoneId: choice.zone } };
      break;
    }
    case 'strike': {
      const strike = options.strikes.find((entry) => entry.attackId === choice.attackId && entry.targets.includes(choice.targetId));
      if (!strike) return refuse('NOT_LISTED');
      if (strike.disabled !== null) return refuse('DISABLED');
      points = strike.cost;
      command = { commandType: 'STRIKE', payload: { attackId: choice.attackId, targetId: choice.targetId } };
      break;
    }
    case 'ability': {
      const ability = options.abilities.find((entry) => entry.abilityId === choice.abilityId);
      const distinct = new Set(choice.targetIds);
      if (!ability || distinct.size !== choice.targetIds.length || choice.targetIds.length > ability.maxTargets ||
        !choice.targetIds.every((target) => ability.targets.includes(target)) || (ability.targets.length > 0 && choice.targetIds.length === 0)) return refuse('NOT_LISTED');
      if (ability.disabled !== null) return refuse('DISABLED');
      points = ability.cost;
      focus = ability.focus;
      command = { commandType: 'COMBAT_USE_ABILITY', payload: { abilityId: choice.abilityId, targetIds: [...choice.targetIds] } };
      break;
    }
    case 'interact': {
      const action = options.actions.find((entry) => entry.actionId === choice.actionId);
      if (!action || (action.targets.length === 0 ? choice.targetId !== null : choice.targetId === null || !action.targets.includes(choice.targetId))) return refuse('NOT_LISTED');
      points = action.cost;
      check = action.check;
      terms = action.terms;
      command = { commandType: 'INTERACT', payload: choice.targetId === null ? { actionId: choice.actionId } : { actionId: choice.actionId, targetId: choice.targetId } };
      break;
    }
    case 'guard': {
      if (options.guard === null) return refuse('NOT_LISTED');
      if (!options.guard.available) return refuse('DISABLED');
      points = options.guard.cost;
      command = { commandType: 'GUARD', payload: {} };
      break;
    }
    case 'end': {
      if (!options.endActivation) return refuse('NOT_LISTED');
      points = left;
      endsTurn = true;
      command = { commandType: 'END_ACTIVATION', payload: {} };
      break;
    }
    case 'speak': {
      // Order matters: the first failing rule is the one the player is told about, and none of them reads the words' meaning.
      if (choice.recipientId === '') return refuse('NO_RECIPIENT');
      if (!options.speechRecipients.includes(choice.recipientId)) return refuse('NOT_ADDRESSABLE');
      if (options.speechRemaining <= 0) return refuse('SPEECH_LIMIT');
      if (choice.text.trim() === '') return refuse('EMPTY_TEXT');
      if (choice.text.length > MAX_SPEECH_TEXT) return refuse('TEXT_TOO_LONG');
      if (UNSTORABLE.test(choice.text)) return refuse('TEXT_INVALID');
      speech = { recipientId: choice.recipientId, text: choice.text, remainingAfter: options.speechRemaining - 1 };
      command = { commandType: 'COMBAT_SPEAK', payload: { recipientId: choice.recipientId, text: choice.text } };
      break;
    }
    case 'answer': {
      const offer = options.offer;
      if (offer === null) return refuse('NOT_LISTED');
      if (choice.decision === 'accept' && !offer.canAccept) return refuse('DISABLED');
      // Declining is always free; accepting pays the terms' declared points, which the server repeats on its own state.
      points = choice.decision === 'accept' ? offer.terms.cost : 0;
      terms = offer.terms;
      decision = choice.decision;
      command = { commandType: 'ANSWER_OFFER', payload: { offerId: offer.offerId, decision: choice.decision } };
      break;
    }
  }
  if (!endsTurn && points > left) return refuse('ACTION_POINTS');
  if (focus > focusLeft) return refuse('FOCUS');
  return { ok: true, command, preview: { choice, points, pointsAfter: endsTurn ? 0 : left - points, focus, focusAfter: focusLeft - focus, endsTurn, check, terms, speech, decision, settles: false, rolls: false } };
}

/** The request body of one command: the closed envelope of docs/13 section 3. */
export interface CommandEnvelope {
  readonly requestId: string;
  readonly expectedRevision: number;
  readonly commandType: string;
  readonly payload: Readonly<Record<string, unknown>>;
}

/** Wrap a validated command with its idempotency identity and the revision the player was looking at. */
export function envelope(command: TacticalCommand, requestId: string, expectedRevision: number): CommandEnvelope {
  return { requestId, expectedRevision, commandType: command.commandType, payload: command.payload };
}
