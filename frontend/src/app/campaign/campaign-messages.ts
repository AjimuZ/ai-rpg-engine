/**
 * Localized wording for campaign errors, operation statuses, receipts, stream notices,
 * source-language fallback disclosure (docs/29 sections 3 and 6) and the resume card (P09.5,
 * docs/08 section 10).
 *
 * Every sentence is a `$localize` message with a stable `campaign.*` ID and named
 * placeholders, so the English source and the reviewed Italian target are checked for exact
 * ID/placeholder parity by `npm run check:i18n`. Server codes are matched against closed
 * allowlists: an unknown code never becomes display text and falls back to a generic
 * sentence. Numeric effects come from the server unchanged and are only formatted for the
 * compiled locale; identifiers such as resource keys and destinations stay literal.
 */
import { AwaitingNarration, DecisionKind, DependencyIssue, OperationStatus, PublicReceipt, ReceiptCalculation, ResumeCard } from './campaign-protocol';

/** Closed set of public error codes the backend can answer for campaign reads, commands and streams. */
const ERRORS: Readonly<Record<string, () => string>> = {
  LOCAL_ADMISSION_DENIED: () => $localize`:@@campaign.error.admissionDenied:This browser session was not admitted. Reload the page to try again.`,
  LOCAL_OWNER_UNCONFIGURED: () => $localize`:@@campaign.error.ownerUnconfigured:The local owner is not configured, so this playthrough cannot be read.`,
  COMMAND_INPUT_MALFORMED: () => $localize`:@@campaign.error.inputMalformed:The request was not understood. Nothing was changed.`,
  COMMAND_BODY_TOO_LARGE: () => $localize`:@@campaign.error.bodyTooLarge:The request was too large. Nothing was changed.`,
  COMMAND_SCOPE_UNAVAILABLE: () => $localize`:@@campaign.error.scopeUnavailable:This playthrough is unavailable.`,
  CAMPAIGN_SCOPE_UNAVAILABLE: () => $localize`:@@campaign.error.scopeUnavailable:This playthrough is unavailable.`,
  CAMPAIGN_READ_INPUT_INVALID: () => $localize`:@@campaign.error.inputMalformed:The request was not understood. Nothing was changed.`,
  COMMAND_REQUEST_CONFLICT: () => $localize`:@@campaign.error.requestConflict:This request identity was already used for a different action. Nothing was changed.`,
  COMMAND_STALE_REVISION: () => $localize`:@@campaign.error.staleRevision:The world has changed since this action was prepared. Refresh the current context before trying again.`,
  COMMAND_CAMPAIGN_BUSY: () => $localize`:@@campaign.error.busy:The playthrough is busy with another action. Wait a moment and check its status before retrying.`,
  COMMAND_ADMISSION_LIMIT: () => $localize`:@@campaign.error.admissionLimit:Too many actions are waiting. Wait for one to finish before sending another.`,
  COMMAND_INVALID: () => $localize`:@@campaign.error.invalid:This action is not valid. Nothing was changed.`,
  COMMAND_MODE_ILLEGAL: () => $localize`:@@campaign.error.modeIllegal:This action is not available in the current situation.`,
  COMMAND_DECISION_UNAVAILABLE: () => $localize`:@@campaign.error.decisionUnavailable:There is no matching decision to answer right now.`,
  COMMAND_ACTOR_UNAVAILABLE: () => $localize`:@@campaign.error.actorUnavailable:The acting character is unavailable.`,
  COMMAND_DESTINATION_UNAVAILABLE: () => $localize`:@@campaign.error.destinationUnavailable:That destination cannot be reached from here.`,
  COMMAND_ABILITY_UNAVAILABLE: () => $localize`:@@campaign.error.abilityUnavailable:That ability is unavailable.`,
  COMMAND_PREREQUISITE_UNMET: () => $localize`:@@campaign.error.prerequisiteUnmet:A requirement for this action is not met.`,
  COMMAND_TARGET_ILLEGAL: () => $localize`:@@campaign.error.targetIllegal:That target is not allowed for this action.`,
  COMMAND_RESOURCE_INSUFFICIENT: () => $localize`:@@campaign.error.resourceInsufficient:There are not enough resources for this action.`,
  COMMAND_CONTENT_UNAVAILABLE: () => $localize`:@@campaign.error.contentUnavailable:The adventure content needed for this action is unavailable.`,
  COMMAND_EFFECT_UNSUPPORTED: () => $localize`:@@campaign.error.effectUnsupported:This kind of effect is not supported yet. Nothing was changed.`,
  COMMAND_DEPENDENCIES_MISSING: () => $localize`:@@campaign.error.dependenciesMissing:This playthrough is paused because adventure content it depends on is missing or damaged. Nothing was changed.`,
  COMMAND_STORAGE_UNAVAILABLE: () => $localize`:@@campaign.error.storageUnavailable:Storage is unavailable right now. Check the action status before retrying.`,
  CAMPAIGN_READ_STORAGE_UNAVAILABLE: () => $localize`:@@campaign.error.storageUnavailable:Storage is unavailable right now. Check the action status before retrying.`,
  CAMPAIGN_READ_CONTENT_UNAVAILABLE: () => $localize`:@@campaign.error.contentUnavailable:The adventure content needed for this action is unavailable.`,
  STREAM_CAPACITY_EXCEEDED: () => $localize`:@@campaign.error.streamCapacity:Too many live updates are open. Close another tab and try again.`,
};

/**
 * Return the localized explanation for a server error code.
 *
 * @param code stable server code, or null when the reply carried none
 * @returns a specific sentence for known codes, otherwise a safe generic sentence; never the raw code
 */
export function campaignErrorMessage(code: string | null): string {
  const known = code !== null && Object.hasOwn(ERRORS, code) ? ERRORS[code] : undefined;
  return known ? known() : $localize`:@@campaign.error.generic:Something went wrong. Nothing was lost; check the playthrough status and try again.`;
}

/** Return the localized one-line state of an operation. */
export function operationStatusMessage(status: OperationStatus): string {
  switch (status) {
    case 'ACCEPTED': return $localize`:@@campaign.status.accepted:Received`;
    case 'PLANNING': return $localize`:@@campaign.status.planning:Being prepared`;
    case 'NEEDS_CLARIFICATION': return $localize`:@@campaign.status.clarification:Waiting for your clarification`;
    case 'VALIDATING': return $localize`:@@campaign.status.validating:Being checked`;
    case 'REJECTED': return $localize`:@@campaign.status.rejected:Not allowed; nothing changed`;
    case 'COMMITTING': return $localize`:@@campaign.status.committing:Being saved`;
    case 'COMMITTED': return $localize`:@@campaign.status.committed:Saved`;
    case 'NARRATING': return $localize`:@@campaign.status.narrating:Saved; narration in progress`;
    case 'DELIVERED': return $localize`:@@campaign.status.delivered:Saved and narrated`;
    case 'COMMITTED_NARRATION_PENDING': return $localize`:@@campaign.status.narrationPending:Saved; narration is pending`;
    case 'CANCELLED': return $localize`:@@campaign.status.cancelled:Cancelled before saving; nothing changed`;
    case 'FAILED_BEFORE_COMMIT': return $localize`:@@campaign.status.failed:Failed before saving; nothing changed`;
  }
}

/** Return the localized reason narration is pending; unknown reasons are rejected by the parser upstream. */
export function narrationReasonMessage(reason: string | null): string {
  switch (reason) {
    case 'NARRATION_UNAVAILABLE': return $localize`:@@campaign.narration.unavailable:Narration is not available yet. The saved result below is complete.`;
    case 'NARRATION_CANCELLED': return $localize`:@@campaign.narration.cancelled:Narration was stopped. The saved result below is complete.`;
    case 'NARRATION_FAILED': return $localize`:@@campaign.narration.failed:Narration could not be produced. The saved result below is complete.`;
    case 'GENERATION_RETIRED': return $localize`:@@campaign.narration.retired:Narration was discarded because the language or settings changed. The saved result below is complete.`;
    default: return $localize`:@@campaign.narration.interrupted:Narration was interrupted. The saved result below is complete.`;
  }
}

/** Return the localized explanation of a stream reset; the client then re-reads operation status. */
export function streamResetMessage(reason: string | null): string {
  switch (reason) {
    case 'CURSOR_EXPIRED': return $localize`:@@campaign.reset.expired:Some live updates were missed while this page was closed. Operation status was checked again.`;
    case 'GENERATION_CHANGED': return $localize`:@@campaign.reset.generation:The playthrough was restored from a backup. Operation status was checked again.`;
    default: return $localize`:@@campaign.reset.unknown:The saved update position was not recognised. Operation status was checked again.`;
  }
}

/** Notice for text shown in a different language than the interface's narrative language. */
export function fallbackNotice(locale: 'en' | 'it'): string {
  return locale === 'it'
    ? $localize`:@@campaign.fallback.italian:This text is shown in the adventure's original Italian because no reviewed translation exists for your language.`
    : $localize`:@@campaign.fallback.english:This text is shown in the adventure's original English because no reviewed translation exists for your language.`;
}

/** Name of the language of a displayed text, for its `lang` attribute label. */
export function languageName(locale: 'en' | 'it'): string {
  return locale === 'it' ? $localize`:@@campaign.language.italian:Italian` : $localize`:@@campaign.language.english:English`;
}

/** Localized label of a known command type; unknown types stay literal identifiers. */
export function commandTypeLabel(commandType: string): string {
  switch (commandType) {
    case 'MOVE': return $localize`:@@campaign.command.move:Move`;
    case 'WAIT': return $localize`:@@campaign.command.wait:Wait`;
    case 'SPEAK': return $localize`:@@campaign.command.speak:Speak`;
    case 'USE_ABILITY': return $localize`:@@campaign.command.useAbility:Use an ability`;
    default: return commandType;
  }
}

/** Describe one calculation value; boolean outcomes become words and totals use locale digits. */
function calculationLine(entry: ReceiptCalculation, format: (value: number) => string): string {
  const path = entry.path;
  const kind = entry.kind;
  if (typeof entry.value === 'boolean') {
    const outcome = entry.value ? $localize`:@@campaign.receipt.succeeded:succeeded` : $localize`:@@campaign.receipt.failed:failed`;
    return $localize`:@@campaign.receipt.check:Check ${path}:path: (${kind}:kind:): ${outcome}:outcome:`;
  }
  const total = format(entry.value);
  return $localize`:@@campaign.receipt.total:Calculation ${path}:path: (${kind}:kind:): ${total}:total:`;
}

/**
 * Describe a public receipt as an ordered list of localized sentences.
 *
 * Numeric values (revisions, resource before/after, draws, elapsed seconds) are exactly those
 * the server committed; only digit grouping follows the compiled locale. The same receipt
 * therefore carries the same mechanics in English and Italian.
 *
 * @param receipt validated receipt
 * @param locale compiled locale used for number formatting
 */
export function receiptLines(receipt: PublicReceipt, locale: string): string[] {
  const numbers = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 });
  const format = (value: number): string => numbers.format(value);
  const lines: string[] = [];
  const revision = format(receipt.stateRevision);
  lines.push($localize`:@@campaign.receipt.revision:Saved as world revision ${revision}:revision:.`);
  if (receipt.destinationId !== null) {
    const destination = receipt.destinationId;
    lines.push($localize`:@@campaign.receipt.moved:Moved to ${destination}:destination:.`);
  }
  if (receipt.elapsedSeconds !== null) {
    const seconds = format(receipt.elapsedSeconds);
    lines.push($localize`:@@campaign.receipt.elapsed:World time passed: ${seconds}:seconds: s.`);
  }
  if (receipt.turnAdvanced !== null) {
    lines.push(receipt.turnAdvanced ? $localize`:@@campaign.receipt.turnAdvanced:The game turn advanced.` : $localize`:@@campaign.receipt.turnHeld:The game turn did not advance.`);
  }
  for (const resource of receipt.resources) {
    const key = resource.resourceKey;
    const before = format(resource.before);
    const after = format(resource.after);
    lines.push($localize`:@@campaign.receipt.resource:${key}:resource:: ${before}:before: → ${after}:after:`);
  }
  for (const calculation of receipt.calculations) lines.push(calculationLine(calculation, format));
  for (const draw of receipt.randomDraws) {
    const ordinal = format(draw.ordinal);
    const value = format(draw.value);
    const sides = format(draw.sides);
    lines.push($localize`:@@campaign.receipt.draw:Draw ${ordinal}:ordinal:: ${value}:value: on a ${sides}:sides:-sided die`);
  }
  return lines;
}

/** Return the localized description of the pending decision shown on the resume card. */
export function decisionMessage(kind: DecisionKind): string {
  switch (kind) {
    case 'STARTING_SITUATION': return $localize`:@@campaign.resume.decisionStart:Decide how to begin, as described in the starting situation.`;
    case 'FREE_INPUT': return $localize`:@@campaign.resume.decisionFree:Say or do what you want next.`;
    default: return $localize`:@@campaign.resume.decisionOther:A decision is waiting for you.`;
  }
}

/** Return the localized state of a saved action whose narration is owed. */
export function awaitingMessage(entry: AwaitingNarration): string {
  return entry.mode === 'IN_PROGRESS'
    ? $localize`:@@campaign.resume.narrationResumes:Saved; its narration resumes by itself.`
    : narrationReasonMessage(entry.reason);
}

/** Return one localized dependency issue; pack coordinates stay literal identifiers. */
export function dependencyIssueMessage(issue: DependencyIssue): string {
  const pack = issue.packId ?? '?';
  const version = issue.packVersion ?? '?';
  switch (issue.code) {
    case 'RULESET_UNAVAILABLE': return $localize`:@@campaign.resume.issueRuleset:${pack}:pack: ${version}:version: was made for rules that this engine version does not provide.`;
    case 'CONTENT_CORRUPT': return $localize`:@@campaign.resume.issueCorrupt:The stored files of ${pack}:pack: ${version}:version: are damaged.`;
    case 'DEPENDENCY_MISSING': return $localize`:@@campaign.resume.issueMissing:${pack}:pack: ${version}:version: is not installed in this adventure.`;
    case 'CLOSURE_INVALID': return $localize`:@@campaign.resume.issueInvalid:${pack}:pack: ${version}:version: cannot be loaded by this engine version.`;
    default: return $localize`:@@campaign.resume.issueRevision:The adventure content of this playthrough is missing.`;
  }
}

/** Return the location line: the localized name when the pack has one, otherwise the literal identifier. */
export function locationMessage(card: ResumeCard): string {
  if (card.location === null) return $localize`:@@campaign.resume.noLocation:Unknown`;
  return card.location.name ?? card.location.id;
}

/** Return the names of the actors the player last saw here, or a localized "nobody" sentence. */
export function presentMessage(card: ResumeCard): string {
  return card.presentActors.length === 0
    ? $localize`:@@campaign.resume.nobody:Nobody you have seen here.`
    : card.presentActors.map((actor) => actor.name).join(', ');
}

/** Return each resource as "key current/maximum" with locale digits; keys stay literal identifiers. */
export function resourceMessages(card: ResumeCard, locale: string): string[] {
  const numbers = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 });
  return card.resources.map((resource) => {
    const key = resource.key;
    const current = numbers.format(resource.current);
    const maximum = numbers.format(resource.maximum);
    return $localize`:@@campaign.resume.resource:${key}:resource: ${current}:current: of ${maximum}:maximum:`;
  });
}
