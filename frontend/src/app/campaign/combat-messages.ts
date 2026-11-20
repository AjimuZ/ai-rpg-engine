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
  CombatEncounter, CombatObjective, CombatOptions, CombatParticipant, CombatReactionOption, CombatVerdict, COMBAT_PHASES,
} from './campaign-protocol';
import { TacticalChoice } from './combat-actions';

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
    readOnly: $localize`:@@combat.readOnly:Reading this panel does not advance time. An action is sent only after you review and confirm it.`,
    sendTitle: $localize`:@@combat.sendTitle:Send an action`,
    sendHelp: $localize`:@@combat.sendHelp:Choosing an action only shows what it would cost. Nothing is sent until you confirm it.`,
    noneToSend: $localize`:@@combat.noneToSend:No action can be sent right now.`,
    previewTitle: $localize`:@@combat.previewTitle:Review before sending`,
    previewFinal: $localize`:@@combat.previewFinal:The game decides the result, and a sent action cannot be undone.`,
    confirm: $localize`:@@combat.confirm:Confirm and send`,
    settleBinds: $localize`:@@combat.settleBinds:Settling confirms the verdict, ends the fight and returns you to the story. It cannot be taken back.`,
    draftKept: $localize`:@@combat.draftKept:Your unsent words stay on this device through a reload or reconnect and are never sent automatically.`,
    cancel: $localize`:@@combat.cancel:Do not send`,
    sending: $localize`:@@combat.sending:Sending…`,
    mapRegion: $localize`:@@combat.mapRegion:Zones and passages, scrollable`,
    chatTitle: $localize`:@@combat.chatTitle:Conversation`,
    chatHelp: $localize`:@@combat.chatHelp:Write to someone in the fight. Words alone change nothing and agree to nothing, and nothing is sent until you review and confirm it.`,
    transcriptTitle: $localize`:@@combat.transcriptTitle:What has been said and answered`,
    noTranscript: $localize`:@@combat.noTranscript:Nothing has been said in this fight yet.`,
    recipientLabel: $localize`:@@combat.recipientLabel:Speaking to`,
    noRecipient: $localize`:@@combat.noRecipient:Nobody chosen`,
    draftLabel: $localize`:@@combat.draftLabel:Your words`,
    reviewSend: $localize`:@@combat.reviewSend:Review and send`,
    clearDraft: $localize`:@@combat.clearDraft:Clear words`,
    quickTitle: $localize`:@@combat.quickTitle:Suggestions that only fill your words`,
    quickNeedsRecipient: $localize`:@@combat.quickNeedsRecipient:Choose who you are speaking to to see suggestions.`,
    replace: $localize`:@@combat.replace:Replace my words`,
    keep: $localize`:@@combat.keep:Keep my words`,
    intentsTitle: $localize`:@@combat.intentsTitle:Talk, negotiate or change the scene`,
    intentsNeedRecipient: $localize`:@@combat.intentsNeedRecipient:Choose who you are speaking to to see the social actions for them.`,
    noIntents: $localize`:@@combat.noIntents:No social or scene action is available now.`,
    acceptBinds: $localize`:@@combat.acceptBinds:Accepting binds you to these terms and cannot be taken back.`,
    declineFree: $localize`:@@combat.declineFree:Declining costs nothing and leaves the fight as it is.`,
  };
}

/**
 * Static labels of the list of encounters the player may start (P13.2-B). Headings shared with the fight's own panel reuse its
 * message IDs, so one sentence has one translation.
 */
export function entryLabels() {
  return {
    title: $localize`:@@combat.offersTitle:Fights you can start here`,
    help: $localize`:@@combat.offersHelp:Starting a fight is always your choice. Choosing one only shows what you can see of it; nothing is sent until you confirm it.`,
    none: $localize`:@@combat.offersNone:No fight can be started here right now.`,
    present: $localize`:@@combat.participants:Participants`,
    escape: $localize`:@@combat.escape:Escape zones`,
    noEscape: $localize`:@@combat.noEscape:This fight has no escape zones.`,
    stakes: $localize`:@@combat.stakes:What is at stake`,
    noStakes: $localize`:@@combat.noObjectives:This fight has no objectives.`,
    previewTitle: $localize`:@@combat.previewTitle:Review before sending`,
    binds: $localize`:@@combat.entryBinds:Starting the fight cannot be taken back. The game decides every result.`,
    confirm: $localize`:@@combat.confirm:Confirm and send`,
    cancel: $localize`:@@combat.cancel:Do not send`,
    sending: $localize`:@@combat.sending:Sending…`,
  };
}

/** Return the label of starting one offered fight, plainly or with an opening attack on one participant. */
export function entryMessage(templateId: string, openingStrike: string | null): string {
  return openingStrike === null ? $localize`:@@combat.start:Start the fight ${templateId}:fight:`
    : $localize`:@@combat.startStrike:Start the fight ${templateId}:fight: by attacking ${participantName(openingStrike)}:target:`;
}

/** Return the zone the player starts an offered fight in. */
export function startZoneMessage(zone: string): string {
  return $localize`:@@combat.startZone:You start in ${zone}:zone:`;
}

/** Return what an offered fight's opening-action policy means for an attack that starts it (docs/11 section 3). */
export function openingPolicyMessage(policy: 'initiative' | 'first' | 'forbidden'): string {
  switch (policy) {
    case 'initiative': return $localize`:@@combat.openingInitiative:An attack that starts this fight resolves at your own turn in the first round.`;
    case 'first': return $localize`:@@combat.openingFirst:An attack that starts this fight resolves before anyone acts in the first round.`;
    case 'forbidden': return $localize`:@@combat.openingForbidden:This fight cannot be started with an attack.`;
  }
}

/** Return the reason the draft cannot be sent now, or null when it can; the draft itself is never touched. */
export function chatBlockMessage(refusal: string): string | null {
  switch (refusal) {
    case 'NOT_YOUR_TURN': return $localize`:@@combat.blockTurn:It is not your turn. You can keep writing; nothing is queued or sent for you.`;
    case 'NO_RECIPIENT': case 'NOT_ADDRESSABLE': return $localize`:@@combat.blockRecipient:Choose someone who can hear you now.`;
    case 'SPEECH_LIMIT': return $localize`:@@combat.blockSpeech:You have already spoken this turn.`;
    case 'EMPTY_TEXT': case 'TEXT_TOO_LONG': case 'TEXT_INVALID': return $localize`:@@combat.blockText:Write between 1 and 500 characters.`;
    default: return null;
  }
}

/** Return how much of the longest allowed text is used. */
export function charactersMessage(used: string, most: string): string {
  return $localize`:@@combat.characters:${used}:used: of ${most}:most: characters`;
}

/** Return the sentence that says whose words were wanted: a quick reply fills this and nothing else. */
export function replaceMessage(words: string): string {
  return $localize`:@@combat.replaceAsk:Replace your words with “${words}:words:”?`;
}

/** Return the player's exact words in a review. */
export function wordsMessage(words: string): string {
  return $localize`:@@combat.words:Your words: “${words}:words:”`;
}

/** Return what speaking costs: no action points, and the allowance left afterwards. */
export function speechSpendMessage(recipient: string, left: string): string {
  return $localize`:@@combat.speechSpend:Speaks to ${recipient}:recipient:. No action points are spent; speeches left this turn afterwards: ${left}:left:.`;
}

/**
 * Return up to three suggested sentences for speaking to a participant of one side. They fill an editable draft and mean nothing
 * until the player sends them; they name no object, place or agreement the projection does not show.
 */
export function quickReplies(side: CombatParticipant['side']): string[] {
  switch (side) {
    case 'hostile': return [
      $localize`:@@combat.quickWhy:Why are you attacking us?`,
      $localize`:@@combat.quickNegotiate:Let's negotiate.`,
      $localize`:@@combat.quickOther:Is there another way to settle this?`,
    ];
    case 'ally': return [
      $localize`:@@combat.quickStay:Stay close to me.`,
      $localize`:@@combat.quickCover:Cover me.`,
      $localize`:@@combat.quickHold:Hold your position.`,
    ];
    case 'player': return [];
  }
}

/** Return the label of a transcript line's kind, so speaker and role never depend on color. */
export function transcriptKindMessage(kind: 'said' | 'attempt' | 'reply' | 'decision' | 'result'): string {
  switch (kind) {
    case 'said': return $localize`:@@combat.kindSaid:Said`;
    case 'attempt': return $localize`:@@combat.kindAttempt:Attempt`;
    case 'reply': return $localize`:@@combat.kindReply:Reply`;
    case 'decision': return $localize`:@@combat.kindDecision:Decision`;
    case 'result': return $localize`:@@combat.kindResult:Result`;
  }
}

/** Return the answer to an offer as a button label, naming the terms it concerns. */
export function answerMessage(decision: 'accept' | 'decline', terms: string): string {
  return decision === 'accept' ? $localize`:@@combat.answerAccept:Accept the terms ${terms}:terms:` : $localize`:@@combat.answerDecline:Decline the terms ${terms}:terms:`;
}

/** Return the named action as the player reads it: its name and target, from the same sentences as the candidate lists. */
export function choiceMessage(choice: TacticalChoice, fight: CombatEncounter | null): string {
  switch (choice.kind) {
    case 'move': return moveMessage(choice.zone);
    case 'strike': return `${strikeMessage(choice.attackId)} · ${targetsMessage(participantName(choice.targetId))}`;
    case 'ability': return choice.targetIds.length === 0 ? abilityMessage(choice.abilityId)
      : `${abilityMessage(choice.abilityId)} · ${targetsMessage(choice.targetIds.map(participantName).join(', '))}`;
    case 'interact': {
      const social = fight?.options?.actions.find((action) => action.actionId === choice.actionId)?.kind === 'social';
      const name = social ? socialMessage(choice.actionId) : sceneMessage(choice.actionId);
      return choice.targetId === null ? name : `${name} · ${targetsMessage(participantName(choice.targetId))}`;
    }
    case 'guard': return $localize`:@@combat.guardOption:Take a guarded stance`;
    case 'end': return $localize`:@@combat.endOption:End your turn`;
    case 'settle': return $localize`:@@combat.settleOption:Settle the fight`;
    case 'speak': return $localize`:@@combat.speakTo:Speak to ${participantName(choice.recipientId)}:recipient:`;
    case 'answer': return answerMessage(choice.decision, fight?.options?.offer?.terms.termsId ?? '?');
  }
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

/** Return one objective as a sentence; an offered encounter's stakes use the same sentences before they have a status. */
export function objectiveMessage(objective: Pick<CombatObjective, 'kind' | 'zone' | 'participantId'>, rounds: string): string {
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

/** Return the action-point price of a previewed action and what remains afterwards. */
export function spendMessage(points: string, after: string): string {
  return $localize`:@@combat.spend:Spends ${points}:points: action points; ${after}:after: left afterwards.`;
}

/** Return the Focus price of a previewed action and what remains afterwards. */
export function spendFocusMessage(focus: string, after: string): string {
  return $localize`:@@combat.spendFocus:Spends ${focus}:focus: Focus; ${after}:after: left afterwards.`;
}

/** Return the consequence of ending the turn: the unused points that are lost. */
export function endsTurnMessage(points: string): string {
  return $localize`:@@combat.endsTurn:Ends your turn. Unused action points that are lost: ${points}:points:. The others act next.`;
}

/**
 * Return the sentence for a refusal and a statement that nothing changed. Accepts the page's own refusal codes and the stable
 * server codes of a refused command; an unknown code gets the generic sentence and is never shown.
 */
export function refusalMessage(code: string | null): string {
  switch (code) {
    case 'NOT_YOUR_TURN': case 'COMMAND_TURN_UNAVAILABLE': case 'COMMAND_MODE_ILLEGAL':
      return $localize`:@@combat.refusedTurn:It is not your turn to act. Nothing changed.`;
    case 'NOT_LISTED': case 'DISABLED': case 'COMMAND_TARGET_ILLEGAL': case 'COMMAND_ZONE_UNAVAILABLE': case 'COMMAND_ATTACK_UNAVAILABLE':
    case 'COMMAND_GUARD_UNAVAILABLE': case 'COMMAND_ABILITY_UNAVAILABLE': case 'COMMAND_INTERACTION_UNAVAILABLE': case 'COMMAND_ACTION_PREVENTED':
      return $localize`:@@combat.refusedUnavailable:That action is not available now. Nothing changed.`;
    case 'ACTION_POINTS': case 'COMMAND_ACTION_POINTS_INSUFFICIENT':
      return $localize`:@@combat.refusedPoints:You do not have enough action points for that. Nothing changed.`;
    case 'FOCUS': case 'COMMAND_RESOURCE_INSUFFICIENT':
      return $localize`:@@combat.refusedFocus:You do not have enough Focus for that. Nothing changed.`;
    case 'COMMAND_STALE_REVISION':
      return $localize`:@@combat.refusedStale:The fight moved on while you were deciding. Review the updated choices. Nothing changed.`;
    case 'NO_RECIPIENT': case 'NOT_ADDRESSABLE':
      return $localize`:@@combat.refusedRecipient:Nobody who can hear you was chosen. Nothing changed.`;
    case 'SPEECH_LIMIT': case 'COMMAND_SPEECH_LIMIT_REACHED':
      return $localize`:@@combat.refusedSpeech:You have already spoken this turn. Nothing changed.`;
    case 'EMPTY_TEXT': case 'TEXT_TOO_LONG': case 'TEXT_INVALID':
      return $localize`:@@combat.refusedText:Those words cannot be sent: write between 1 and 500 characters. Nothing changed.`;
    case 'COMMAND_OFFER_UNAVAILABLE':
      return $localize`:@@combat.refusedOffer:That offer is no longer on the table. Nothing changed.`;
    case 'COMMAND_ENCOUNTER_UNAVAILABLE':
      return $localize`:@@combat.refusedEntry:That fight cannot be started now. Nothing changed.`;
    case 'COMMAND_CAMPAIGN_BUSY': case 'COMMAND_ADMISSION_LIMIT':
      return $localize`:@@combat.refusedBusy:The game is busy with another action. Try again in a moment. Nothing changed.`;
    case 'LOST':
      return $localize`:@@combat.sendLost:The reply was lost. If you repeat the action, it is played at most once.`;
    default: return $localize`:@@combat.refusedGeneric:The game refused that action. Nothing changed.`;
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
