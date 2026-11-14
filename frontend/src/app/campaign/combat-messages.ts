/**
 * Localized wording of the combat panel (P12.1-B, docs/13 "P12.1-B combat panel"): phases, sides, standings, cover, passages,
 * objectives, verdicts, the player's legal candidates and the stable reasons one is disabled.
 *
 * Every sentence is a `$localize` message with a stable `combat.*` ID and named placeholders, checked for exact ID and
 * placeholder parity with the reviewed Italian catalog by `npm run check:i18n`. Server enumerations are matched exhaustively
 * (the parser already refuses unknown ones) except disabled reasons, which are an open stable-code list: an unknown reason
 * falls back to a generic sentence and its raw code is never shown. Numbers arrive preformatted for the compiled locale;
 * roster, zone, attack, ability and effect identifiers stay literal because frozen fights carry no display names.
 */
import {
  CombatObjective, CombatOptions, CombatParticipant, CombatReactionOption, CombatVerdict, COMBAT_PHASES,
} from './campaign-protocol';

type Phase = (typeof COMBAT_PHASES)[number];

/** Static headings and labels of the panel, evaluated when the panel is built so they follow the compiled locale. */
export function combatLabels() {
  return {
    title: $localize`:@@combat.title:Combat`,
    initiative: $localize`:@@combat.initiative:Initiative order`,
    noInitiative: $localize`:@@combat.noInitiative:Initiative has not been rolled yet.`,
    zones: $localize`:@@combat.zones:Zones and passages`,
    escape: $localize`:@@combat.escape:Escape zones`,
    noEscape: $localize`:@@combat.noEscape:This fight has no escape zones.`,
    participants: $localize`:@@combat.participants:Participants`,
    you: $localize`:@@combat.you:You`,
    yourValues: $localize`:@@combat.yourValues:Your fighting values`,
    sheetNote: $localize`:@@combat.sheetNote:Guard, Resistance and armor are the sheet values, before stance, cover and effects.`,
    health: $localize`:@@combat.health:Health`,
    focus: $localize`:@@combat.focus:Focus`,
    guard: $localize`:@@combat.guard:Guard`,
    resistance: $localize`:@@combat.resistance:Resistance`,
    armor: $localize`:@@combat.armor:Armor`,
    stance: $localize`:@@combat.stance:Guard stance`,
    stanceHeld: $localize`:@@combat.stanceHeld:Held until your next turn`,
    stanceNone: $localize`:@@combat.stanceNone:Not held`,
    reactionsLeft: $localize`:@@combat.reactionsLeft:Reactions left this round`,
    effects: $localize`:@@combat.effects:Effects`,
    noEffects: $localize`:@@combat.noEffects:No active effects.`,
    known: $localize`:@@combat.known:Known defenses`,
    notAssessed: $localize`:@@combat.notAssessed:Not assessed yet.`,
    objectives: $localize`:@@combat.objectives:Objectives`,
    noObjectives: $localize`:@@combat.noObjectives:This fight has no objectives.`,
    options: $localize`:@@combat.options:What you can do now`,
    noDecision: $localize`:@@combat.noDecision:Nothing is waiting for you to decide right now.`,
    movement: $localize`:@@combat.movement:Movement`,
    attacks: $localize`:@@combat.attacks:Attacks`,
    abilities: $localize`:@@combat.abilities:Abilities`,
    declared: $localize`:@@combat.declared:Social and scene actions`,
    other: $localize`:@@combat.other:Other actions`,
    reactions: $localize`:@@combat.reactions:Reactions`,
    guardOption: $localize`:@@combat.guardOption:Take a guarded stance`,
    itemOption: $localize`:@@combat.itemOption:Use an item`,
    readyOption: $localize`:@@combat.readyOption:Ready or change equipment`,
    assessOption: $localize`:@@combat.assessOption:Assess a participant`,
    endOption: $localize`:@@combat.endOption:End your turn`,
    offer: $localize`:@@combat.offer:Offer on the table`,
    canAccept: $localize`:@@combat.canAccept:You can accept it.`,
    cannotAccept: $localize`:@@combat.cannotAccept:You cannot accept it right now.`,
    verdict: $localize`:@@combat.verdict:Verdict awaiting settlement`,
    verdictHelp: $localize`:@@combat.verdictHelp:The fight is decided. Nothing more changes until you settle it.`,
    roundCapNote: $localize`:@@combat.roundCapNote:Decided by the round limit.`,
    unavailable: $localize`:@@combat.unavailable:Not available now`,
    passable: $localize`:@@combat.passable:Open`,
    blocked: $localize`:@@combat.blocked:Blocked`,
    difficult: $localize`:@@combat.difficult:Difficult terrain`,
    obscured: $localize`:@@combat.obscured:Obscured`,
    unlimited: $localize`:@@combat.unlimited:No occupant limit`,
    readOnly: $localize`:@@combat.readOnly:This panel shows the saved state only. Reading it does not advance time or send anything.`,
    mapRegion: $localize`:@@combat.mapRegion:Zones and passages, scrollable`,
  };
}

/** One-line state of the fight for a phase; `playerDecision` marks the player's own decision point. */
export function phaseMessage(phase: Phase, playerDecision: boolean): string {
  switch (phase) {
    case 'PROPOSED': case 'VALIDATED': case 'REJECTED': case 'ENTERING':
      return $localize`:@@combat.phaseSetup:The fight is being set up.`;
    case 'ROUND_OPEN': case 'ACTOR_START': case 'RESOLVING': case 'ACTOR_END': case 'ROUND_END':
      return $localize`:@@combat.phaseRound:The round is being played.`;
    case 'AWAITING_ACTION':
      return playerDecision ? $localize`:@@combat.phaseYourTurn:Your turn: choose what to do.` : $localize`:@@combat.phaseWaiting:Waiting for an action.`;
    case 'OUTCOME_PENDING': return $localize`:@@combat.phaseDecided:The fight is decided and waits for you to settle it.`;
    case 'SETTLED': case 'NARRATIVE_RESUME': return $localize`:@@combat.phaseEnded:The fight has ended.`;
  }
}

/** Return the round reading, or the pre-initiative sentence while the round is 0. */
export function roundMessage(round: string, cap: string, started: boolean): string {
  return started ? $localize`:@@combat.round:Round ${round}:round: of ${cap}:cap:` : $localize`:@@combat.beforeRound:Before the first round`;
}

/** Return who is acting and how many action points are left. */
export function actingMessage(who: string, points: string, total: string): string {
  return $localize`:@@combat.acting:Acting: ${who}:who:, ${points}:points: of ${total}:total: action points left`;
}

/** Return a participant's name: the player is "You", everyone else keeps the roster identifier. */
export function participantName(id: string): string {
  return id === 'player' ? $localize`:@@combat.you:You` : id;
}

/** Return the initiative total of one place in the order. */
export function initiativeMessage(value: string): string {
  return $localize`:@@combat.initiativeValue:Initiative ${value}:value:`;
}

export function sideMessage(side: CombatParticipant['side']): string {
  switch (side) {
    case 'player': return $localize`:@@combat.sidePlayer:Your side`;
    case 'ally': return $localize`:@@combat.sideAlly:Ally`;
    case 'hostile': return $localize`:@@combat.sideHostile:Hostile`;
  }
}

export function standingMessage(status: CombatParticipant['status']): string {
  switch (status) {
    case 'ACTIVE': return $localize`:@@combat.standingActive:Active`;
    case 'DEFEATED': return $localize`:@@combat.standingDefeated:Defeated`;
    case 'ESCAPED': return $localize`:@@combat.standingEscaped:Escaped`;
    case 'SURRENDERED': return $localize`:@@combat.standingSurrendered:Surrendered`;
  }
}

export function coverMessage(cover: 'none' | 'partial' | 'full'): string {
  switch (cover) {
    case 'none': return $localize`:@@combat.coverNone:No cover`;
    case 'partial': return $localize`:@@combat.coverPartial:Partial cover`;
    case 'full': return $localize`:@@combat.coverFull:Full cover`;
  }
}

/** Return the zone's occupant limit; 0 means unbounded and is worded by {@link combatLabels}. */
export function capacityMessage(limit: string): string {
  return $localize`:@@combat.capacity:Holds at most ${limit}:limit:`;
}

/** Return the cost of crossing a passage in action points. */
export function crossingMessage(cost: string): string {
  return $localize`:@@combat.crossing:Action points to cross: ${cost}:cost:`;
}

/** Return the price of one candidate in action points. */
export function priceMessage(cost: string): string {
  return $localize`:@@combat.price:Action points: ${cost}:cost:`;
}

/** Return when a known defense was learned, and that Health may have changed since. */
export function learnedMessage(round: string, revision: string): string {
  return $localize`:@@combat.learned:Learned in round ${round}:round: (revision ${revision}:revision:). Health may have changed since.`;
}

/** Return one objective as a sentence. */
export function objectiveMessage(objective: CombatObjective, rounds: string): string {
  const zone = objective.zone ?? '?';
  const who = participantName(objective.participantId ?? '?');
  switch (objective.kind) {
    case 'defeatHostiles': return $localize`:@@combat.objectiveDefeat:Defeat every hostile participant`;
    case 'escape': return $localize`:@@combat.objectiveEscape:Escape from the fight`;
    case 'reachZone': return $localize`:@@combat.objectiveReach:Reach zone ${zone}:zone:`;
    case 'survive': return $localize`:@@combat.objectiveSurvive:Survive ${rounds}:rounds: rounds`;
    case 'protect': return $localize`:@@combat.objectiveProtect:Protect ${who}:who:`;
  }
}

export function objectiveStatusMessage(status: CombatObjective['status']): string {
  switch (status) {
    case 'pending': return $localize`:@@combat.statusPending:Pending`;
    case 'met': return $localize`:@@combat.statusMet:Met`;
    case 'failed': return $localize`:@@combat.statusFailed:Failed`;
  }
}

export function verdictMessage(outcome: CombatVerdict['outcome']): string {
  switch (outcome) {
    case 'VICTORY': return $localize`:@@combat.outcomeVictory:Victory`;
    case 'DEFEAT': return $localize`:@@combat.outcomeDefeat:Defeat`;
    case 'ESCAPE': return $localize`:@@combat.outcomeEscape:Escape`;
    case 'OBJECTIVE': return $localize`:@@combat.outcomeObjective:Objective achieved`;
    case 'PLAYER_SURRENDER': return $localize`:@@combat.outcomePlayerSurrender:You surrendered`;
    case 'HOSTILE_SURRENDER': return $localize`:@@combat.outcomeHostileSurrender:The opposition surrendered`;
    case 'AGREEMENT': return $localize`:@@combat.outcomeAgreement:Agreement reached`;
    case 'STALEMATE': return $localize`:@@combat.outcomeStalemate:Stalemate`;
  }
}

/** Return the sentence for a stable disabled reason; an unknown reason gets the generic sentence, never its code. */
export function disabledMessage(code: string): string {
  switch (code) {
    case 'NO_AMMUNITION': return $localize`:@@combat.disabledAmmunition:No ammunition left`;
    case 'NO_TARGET_IN_REACH': return $localize`:@@combat.disabledTarget:No target in reach`;
    case 'ACTION_POINTS': return $localize`:@@combat.disabledPoints:Not enough action points`;
    case 'PREVENTED': return $localize`:@@combat.disabledPrevented:An effect prevents this`;
    case 'FOCUS': return $localize`:@@combat.disabledFocus:Not enough Focus`;
    case 'LIMIT': return $localize`:@@combat.disabledLimit:Already used as often as allowed`;
    case 'COOLDOWN': return $localize`:@@combat.disabledCooldown:Still recovering`;
    default: return $localize`:@@combat.unavailable:Not available now`;
  }
}

export function moveMessage(zone: string): string { return $localize`:@@combat.move:Move to ${zone}:zone:`; }
export function withdrawMessage(zone: string): string { return $localize`:@@combat.withdraw:Withdraw to ${zone}:zone:`; }
export function strikeMessage(attack: string): string { return $localize`:@@combat.strike:Attack with ${attack}:attack:`; }
export function abilityMessage(ability: string): string { return $localize`:@@combat.ability:Use the ability ${ability}:ability:`; }
export function focusCostMessage(focus: string): string { return $localize`:@@combat.focusCost:Focus: ${focus}:focus:`; }
export function maxTargetsMessage(most: string): string { return $localize`:@@combat.maxTargets:Targets at most: ${most}:most:`; }
export function targetsMessage(targets: string): string { return $localize`:@@combat.targets:Targets: ${targets}:targets:`; }
export function recipientsMessage(recipients: string): string { return $localize`:@@combat.recipients:Recipients: ${recipients}:recipients:`; }
export function speechMessage(left: string): string { return $localize`:@@combat.speech:Speak: ${left}:left: left this turn`; }
export function socialMessage(action: string): string { return $localize`:@@combat.social:Social action ${action}:action:`; }
export function sceneMessage(action: string): string { return $localize`:@@combat.scene:Scene action ${action}:action:`; }

/** Return the declared check of an action, with its skill when it names one. */
export function checkMessage(attribute: string, skill: string | null, rank: string): string {
  return skill === null ? $localize`:@@combat.check:Check on ${attribute}:attribute:, rank ${rank}:rank:`
    : $localize`:@@combat.checkSkill:Check on ${attribute}:attribute: and ${skill}:skill:, rank ${rank}:rank:`;
}

/** Return declared terms with their kind and cost. */
export function termsMessage(terms: string, kind: string, cost: string): string {
  return $localize`:@@combat.terms:Terms ${terms}:terms: (${kind}:kind:), cost ${cost}:cost:`;
}

/** Return the terms waiting on the table and who they are with. */
export function offerMessage(terms: string, recipient: string): string {
  return $localize`:@@combat.offerLine:Terms ${terms}:terms: are on the table with ${recipient}:recipient:.`;
}

/** Return the firing rule of a reaction. */
export function reactionPolicyMessage(reaction: CombatReactionOption, value: string): string {
  switch (reaction.mode) {
    case 'off': return $localize`:@@combat.policyOff:Never used`;
    case 'first': return $localize`:@@combat.policyFirst:Used at the first chance`;
    case 'healthAtMost': return $localize`:@@combat.policyHealth:Used when Health is at most ${value}:value: percent`;
    case 'focusAtLeast': return $localize`:@@combat.policyFocus:Used when Focus is at least ${value}:value:`;
  }
}

/** Whether the options list holds anything besides ending the turn, so the panel can say when the player has nothing to choose. */
export function hasChoices(options: CombatOptions): boolean {
  return options.moves.length > 0 || options.strikes.length > 0 || options.abilities.length > 0 || options.actions.length > 0 ||
    options.withdraw.length > 0 || options.guard !== null || options.useItem !== null || options.ready !== null || options.assess !== null;
}
