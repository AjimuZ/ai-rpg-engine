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
  | { readonly kind: 'end' };

/** Stable refusal codes of the page's own validation; they never carry server text. */
export const TACTICAL_REFUSALS = ['NOT_YOUR_TURN', 'NOT_LISTED', 'DISABLED', 'ACTION_POINTS', 'FOCUS'] as const;
export type TacticalRefusal = (typeof TACTICAL_REFUSALS)[number];

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
  }
}

/**
 * List every named action the projection offers at the player's decision, one entry per action and target.
 *
 * @returns an empty list outside the player's decision; disabled entries stay listed so their reason is visible
 */
export function tacticalEntries(fight: CombatEncounter): TacticalEntry[] {
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
  const options = fight?.options ?? null;
  if (fight === null || !fight.playerDecision || options === null || fight.actionPoints === null) return refuse('NOT_YOUR_TURN');
  const left = fight.actionPoints;
  const focusLeft = fight.player.focus;
  let points = 0;
  let focus = 0;
  let endsTurn = false;
  let check: TacticalPreview['check'] = null;
  let terms: TacticalPreview['terms'] = null;
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
  }
  if (!endsTurn && points > left) return refuse('ACTION_POINTS');
  if (focus > focusLeft) return refuse('FOCUS');
  return { ok: true, command, preview: { choice, points, pointsAfter: endsTurn ? 0 : left - points, focus, focusAfter: focusLeft - focus, endsTurn, check, terms } };
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
