/**
 * Validators and value types for the browser side of the campaign operation protocol
 * (docs/13 sections 3–4, docs/29 section 6): operation bodies, public receipts, stream
 * frames, stream cursors, the campaign-state fields the interface renders and, since P09.5,
 * the resume card (docs/08 section 10).
 *
 * Every parser fails closed with {@link CampaignProtocolError}: a payload that is malformed,
 * oversized, foreign or carries an unknown enumeration never reaches a screen as partial
 * state. Numeric effects and identities are carried as the server sent them; this module
 * never formats or translates anything (see `campaign-messages.ts`).
 */
import { isUuid } from '../library/saved-library-client';

/** Operation lifecycle statuses published by the backend (docs/07 section 2). */
export const OPERATION_STATUSES = [
  'ACCEPTED', 'PLANNING', 'NEEDS_CLARIFICATION', 'VALIDATING', 'REJECTED', 'COMMITTING', 'COMMITTED',
  'NARRATING', 'DELIVERED', 'COMMITTED_NARRATION_PENDING', 'CANCELLED', 'FAILED_BEFORE_COMMIT',
] as const;
export type OperationStatus = (typeof OPERATION_STATUSES)[number];

/** Statuses after which no further transition exists; any other status must still be reconciled. */
const TERMINAL: ReadonlySet<OperationStatus> = new Set<OperationStatus>([
  'NEEDS_CLARIFICATION', 'REJECTED', 'DELIVERED', 'COMMITTED_NARRATION_PENDING', 'CANCELLED', 'FAILED_BEFORE_COMMIT']);

/** Return true when the operation can still change and therefore needs a status query after a reload. */
export function isPending(status: OperationStatus): boolean {
  return !TERMINAL.has(status);
}

/** Stream message types, including the two control messages (docs/13 section 4). */
export const STREAM_TYPES = [
  'stream.ready', 'stream.reset', 'operation.accepted', 'operation.progress', 'operation.clarification',
  'operation.rejected', 'operation.cancelled', 'operation.failed', 'state.committed', 'narrative.ready',
  'narrative.pending', 'presentation.event',
] as const;
export type StreamType = (typeof STREAM_TYPES)[number];

/** Presentation event kinds (docs/13 section 4); an unknown kind is refused, not displayed. */
export const PRESENTATION_KINDS = ['SCENE', 'POSE', 'LINE_AUDIO'] as const;
/** Same shape the backend admits: a plain reference cannot be a URL, a path, markup or a script. */
const PRESENTATION_REFERENCE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/** Reasons a stream reset or a pending narration can name; unknown values are rejected, not displayed. */
export const RESET_REASONS = ['CURSOR_EXPIRED', 'CURSOR_UNKNOWN', 'GENERATION_CHANGED'] as const;
export const NARRATION_REASONS = [
  'NARRATION_UNAVAILABLE', 'NARRATION_CANCELLED', 'NARRATION_FAILED', 'GENERATION_RETIRED', 'NARRATION_INTERRUPTED'] as const;

/** Failure categories carried by {@link CampaignProtocolError}. */
export type CampaignFailure = 'invalid' | 'api' | 'unavailable' | 'aborted';

/**
 * Typed transport/protocol failure. `code` is the server's stable error code when the body
 * was a well-formed `{code}` object, otherwise null; it is never free text from the server.
 */
export class CampaignProtocolError extends Error {
  /** Create a failure; `message` is diagnostic only and is never rendered. */
  constructor(readonly kind: CampaignFailure, message: string, readonly status = 0, readonly code: string | null = null) {
    super(message);
  }
}

/** Throw an `invalid` protocol failure for one field. */
function invalid(field: string): never {
  throw new CampaignProtocolError('invalid', `Invalid campaign field: ${field}`);
}

/** Read a plain JSON object; arrays, null and primitives are rejected. */
function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(field);
  return value as Record<string, unknown>;
}

/** Read a non-negative safe integer; larger values could not round-trip through JSON exactly. */
function count(source: Record<string, unknown>, field: string): number {
  const value = source[field];
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) invalid(field);
  return value as number;
}

/** Read a signed safe integer, used for resource values that a receipt reports before and after. */
function integer(source: Record<string, unknown>, field: string): number {
  const value = source[field];
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) invalid(field);
  return value as number;
}

/** Read a bounded non-blank identifier string (definition keys, command types, destinations). */
function token(source: Record<string, unknown>, field: string, max = 120): string {
  const value = source[field];
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) invalid(field);
  return value as string;
}

/** Read a canonical UUID field. */
function uuid(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return isUuid(value) ? value : invalid(field);
}

/** Read a boolean field. */
function flag(source: Record<string, unknown>, field: string): boolean {
  const value = source[field];
  if (typeof value !== 'boolean') invalid(field);
  return value as boolean;
}

/** Read an array bounded to `max` entries. */
function list(source: Record<string, unknown>, field: string, max: number): unknown[] {
  const value = source[field];
  if (!Array.isArray(value) || value.length > max) invalid(field);
  return value as unknown[];
}

/** Parsed stream identity: the campaign recovery generation and the per-campaign sequence. */
export interface Cursor { readonly generation: number; readonly sequence: number }

const CURSOR = /^(0|[1-9][0-9]{0,15}):(0|[1-9][0-9]{0,15})$/;

/** Parse a canonical `generation:sequence` identity; any other spelling returns undefined. */
export function parseCursor(value: unknown): Cursor | undefined {
  const match = typeof value === 'string' ? CURSOR.exec(value) : null;
  if (!match) return undefined;
  const generation = Number(match[1]);
  const sequence = Number(match[2]);
  return Number.isSafeInteger(generation) && Number.isSafeInteger(sequence) ? { generation, sequence } : undefined;
}

/** Order two identities; identities from different generations are not comparable and return undefined. */
export function compareCursor(a: Cursor, b: Cursor): number | undefined {
  return a.generation === b.generation ? Math.sign(a.sequence - b.sequence) : undefined;
}

/** One resource change from the acting player's own receipt. */
export interface ReceiptResource { readonly resourceKey: string; readonly before: number; readonly after: number }
/** One calculation value; booleans are outcomes (success), numbers are totals. */
export interface ReceiptCalculation {
  readonly path: string; readonly kind: string; readonly value: number | boolean; readonly drawOrdinals: readonly number[];
}
/** One committed random draw: ordinal, number of sides and the drawn value. */
export interface ReceiptDraw { readonly ordinal: number; readonly sides: number; readonly value: number }

/** Player-safe receipt projection; optional fields exist only for locally resolved actions. */
export interface PublicReceipt {
  readonly stateRevision: number;
  readonly firstEventSequence: number;
  readonly lastEventSequence: number;
  readonly commandType: string | null;
  readonly elapsedSeconds: number | null;
  readonly turnAdvanced: boolean | null;
  readonly destinationId: string | null;
  readonly resources: readonly ReceiptResource[];
  readonly calculations: readonly ReceiptCalculation[];
  readonly randomDraws: readonly ReceiptDraw[];
}

/** Validate a receipt projection; an unexpected shape rejects the whole receipt. */
export function parseReceipt(value: unknown): PublicReceipt {
  const source = record(value, 'receipt');
  const optionalList = (field: string): unknown[] => field in source ? list(source, field, 64) : [];
  return {
    stateRevision: count(source, 'stateRevision'),
    firstEventSequence: count(source, 'firstEventSequence'),
    lastEventSequence: count(source, 'lastEventSequence'),
    commandType: 'commandType' in source ? token(source, 'commandType') : null,
    elapsedSeconds: 'elapsedSeconds' in source ? count(source, 'elapsedSeconds') : null,
    turnAdvanced: 'turnAdvanced' in source ? flag(source, 'turnAdvanced') : null,
    destinationId: 'destinationId' in source ? token(source, 'destinationId') : null,
    resources: optionalList('resources').map(/** Resource key plus exact before/after values. */ (entry) => {
      const row = record(entry, 'resources');
      return { resourceKey: token(row, 'resourceKey'), before: integer(row, 'before'), after: integer(row, 'after') };
    }),
    calculations: optionalList('calculations').map(/** Calculation values are integers or booleans. */ (entry) => {
      const row = record(entry, 'calculations');
      const result = row['value'];
      if (typeof result !== 'boolean' && (typeof result !== 'number' || !Number.isSafeInteger(result))) invalid('value');
      return {
        path: token(row, 'path', 200), kind: token(row, 'kind'), value: result as number | boolean,
        drawOrdinals: list(row, 'drawOrdinals', 64).map(/** Ordinals are draw positions. */ (ordinal) => {
          if (typeof ordinal !== 'number' || !Number.isSafeInteger(ordinal) || ordinal < 0) invalid('drawOrdinals');
          return ordinal as number;
        }),
      };
    }),
    randomDraws: optionalList('randomDraws').map(/** Draws never expose seeds or algorithms. */ (entry) => {
      const row = record(entry, 'randomDraws');
      return { ordinal: count(row, 'ordinal'), sides: count(row, 'sides'), value: count(row, 'value') };
    }),
  };
}

/** Public operation view returned by submit, status and cancel endpoints. */
export interface OperationView {
  readonly operationId: string;
  readonly campaignId: string;
  readonly commandType: string;
  readonly status: OperationStatus;
  readonly baseRevision: number;
  readonly committedRevision: number | null;
  readonly operationVersion: number;
  readonly receipt: PublicReceipt | null;
}

/** Read a status from the closed lifecycle enumeration. */
function status(source: Record<string, unknown>, field: string): OperationStatus {
  const value = source[field];
  return OPERATION_STATUSES.includes(value as OperationStatus) ? value as OperationStatus : invalid(field);
}

/**
 * Validate an operation body for one campaign.
 *
 * @param value decoded JSON body
 * @param campaignId the campaign the caller asked about; a body for another campaign is foreign
 * @throws CampaignProtocolError `invalid` for malformed or foreign bodies
 */
export function parseOperation(value: unknown, campaignId: string): OperationView {
  const source = record(value, 'operation');
  const committed = source['committedRevision'];
  if (committed !== null && !(typeof committed === 'number' && Number.isSafeInteger(committed) && committed >= 0)) invalid('committedRevision');
  const view: OperationView = {
    operationId: uuid(source, 'operationId'), campaignId: uuid(source, 'campaignId'),
    commandType: token(source, 'commandType'), status: status(source, 'status'),
    baseRevision: count(source, 'baseRevision'), committedRevision: committed as number | null,
    operationVersion: count(source, 'operationVersion'),
    receipt: source['receipt'] === null || source['receipt'] === undefined ? null : parseReceipt(source['receipt']),
  };
  if (view.campaignId !== campaignId) invalid('campaignId');
  return view;
}

/**
 * One typed presentation cue. `deliveryId` stays the same on every delivery of the cue. This only
 * validates and carries a cue; ordering and deduplication are in `presentation-cues.ts` and display
 * is in `campaign-cues.ts`.
 */
export interface PresentationCue {
  readonly deliveryId: string;
  readonly kind: (typeof PRESENTATION_KINDS)[number];
  readonly ordinal: number;
  readonly sceneId: string | null;
  readonly actorId: string | null;
  readonly poseId: string | null;
  readonly expressionId: string | null;
  readonly lineId: string | null;
  readonly assetId: string | null;
}

/** Read an optional plain content reference; anything but a plain reference is refused. */
function reference(source: Record<string, unknown>, field: string): string | null {
  const value = source[field];
  if (value === undefined) return null;
  return typeof value === 'string' && PRESENTATION_REFERENCE.test(value) ? value : invalid(field);
}

/** Validate a cue's allowlisted fields, binding its delivery identity to the frame that carried it. */
function parseCue(source: Record<string, unknown>, id: string, operationId: string): PresentationCue {
  const ordinal = count(source, 'ordinal');
  if (source['deliveryId'] !== `${id}:${operationId}:${ordinal}`) invalid('deliveryId');
  return {
    deliveryId: source['deliveryId'] as string, kind: choice(source, 'kind', PRESENTATION_KINDS), ordinal,
    sceneId: reference(source, 'sceneId'),
    actorId: source['actorId'] === undefined ? null : uuid(source, 'actorId'),
    poseId: reference(source, 'poseId'), expressionId: reference(source, 'expressionId'),
    lineId: reference(source, 'lineId'), assetId: reference(source, 'assetId'),
  };
}

/** One validated stream message. `reason` appears on resets and pending narration only; `presentation` on cues only. */
export interface StreamEvent {
  readonly id: string;
  readonly cursor: Cursor;
  readonly type: StreamType;
  readonly campaignId: string;
  readonly stateRevision: number;
  readonly operationId: string | null;
  readonly commandType: string | null;
  readonly status: OperationStatus | null;
  readonly operationVersion: number | null;
  readonly receipt: PublicReceipt | null;
  readonly reason: string | null;
  readonly presentation: PresentationCue | null;
}

/** Validate one SSE frame's identity, type and JSON data for the expected campaign. */
export function parseStreamEvent(id: string, type: string, data: string, campaignId: string): StreamEvent {
  const cursor = parseCursor(id);
  if (!cursor || !STREAM_TYPES.includes(type as StreamType)) invalid('stream frame');
  let decoded: unknown;
  try { decoded = JSON.parse(data); } catch { invalid('stream data'); }
  const source = record(decoded, 'stream data');
  const control = type === 'stream.ready' || type === 'stream.reset';
  const reason = source['reason'];
  const allowed: readonly string[] = type === 'stream.reset' ? RESET_REASONS : type === 'narrative.pending' ? NARRATION_REASONS : [];
  if (reason !== undefined && (typeof reason !== 'string' || !allowed.includes(reason))) invalid('reason');
  if (type === 'stream.reset' && reason === undefined) invalid('reason');
  const event: StreamEvent = {
    id, cursor: cursor!, type: type as StreamType, campaignId: uuid(source, 'campaignId'),
    stateRevision: count(source, 'stateRevision'),
    operationId: control ? null : uuid(source, 'operationId'),
    commandType: control ? null : token(source, 'commandType'),
    status: control ? null : status(source, 'status'),
    operationVersion: control ? null : count(source, 'operationVersion'),
    receipt: type === 'state.committed' ? parseReceipt(source['receipt']) : null,
    reason: (reason as string | undefined) ?? null,
    presentation: type === 'presentation.event' ? parseCue(source, id, uuid(source, 'operationId')) : null,
  };
  if (event.campaignId !== campaignId) invalid('campaignId');
  return event;
}

/** Maximum bytes accepted for a single frame and for one response; larger streams are cut and resumed. */
export const MAX_FRAME_CHARS = 64 * 1024;
export const MAX_RESPONSE_CHARS = 1024 * 1024;

/**
 * Incremental Server-Sent Events decoder for the bounded long poll.
 *
 * Feed decoded text chunks with {@link push}; complete frames (blank-line terminated) are
 * returned as soon as they arrive. Comment lines (heartbeats) and `retry:` fields are
 * ignored. A frame without an id or event is invalid, so a proxy-injected body cannot be
 * mistaken for a message. Oversized frames or responses throw `invalid`.
 */
export class FrameDecoder {
  private buffer = '';
  private received = 0;

  /** @param campaignId campaign whose frames are accepted; frames for another campaign are invalid */
  constructor(private readonly campaignId: string) {}

  /** Append text and return every newly completed, validated event in arrival order. */
  push(text: string): StreamEvent[] {
    this.received += text.length;
    if (this.received > MAX_RESPONSE_CHARS) invalid('stream response size');
    this.buffer += text.replace(/\r\n?/g, '\n');
    const events: StreamEvent[] = [];
    let end = this.buffer.indexOf('\n\n');
    while (end >= 0) {
      const frame = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const event = this.frame(frame);
      if (event) events.push(event);
      end = this.buffer.indexOf('\n\n');
    }
    if (this.buffer.length > MAX_FRAME_CHARS) invalid('stream frame size');
    return events;
  }

  /** Decode one frame; comment-only and retry-only frames return undefined. */
  private frame(frame: string): StreamEvent | undefined {
    let id: string | undefined;
    let type: string | undefined;
    const data: string[] = [];
    for (const line of frame.split('\n')) {
      if (line === '' || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'id') id = value;
      else if (field === 'event') type = value;
      else if (field === 'data') data.push(value);
      else if (field !== 'retry') invalid('stream field');
    }
    if (id === undefined && type === undefined && data.length === 0) return undefined;
    if (id === undefined || type === undefined || data.length === 0) invalid('stream frame');
    return parseStreamEvent(id!, type!, data.join('\n'), this.campaignId);
  }
}

/** Public starting situation with its actual language and explicit source-language fallback flag. */
export interface StartingSituation { readonly text: string; readonly locale: 'en' | 'it'; readonly fallback: boolean }

/** The subset of the campaign state the interface renders; hidden or private fields are never read. */
export interface CampaignView {
  readonly adventureId: string;
  readonly campaignId: string;
  readonly title: string;
  readonly stateRevision: number;
  readonly startingSituation: StartingSituation | null;
}

/**
 * Validate a campaign state body against the route's scope.
 *
 * @param value decoded JSON body of the state endpoint
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @throws CampaignProtocolError `invalid` when the body names any other scope
 */
export function parseCampaignView(value: unknown, adventureId: string, campaignId: string): CampaignView {
  const source = record(value, 'state');
  const scope = record(source['scope'], 'scope');
  const summary = record(source['campaign'], 'campaign');
  if (uuid(scope, 'adventureId') !== adventureId || uuid(scope, 'campaignId') !== campaignId ||
    uuid(summary, 'adventureId') !== adventureId || uuid(summary, 'campaignId') !== campaignId) invalid('scope');
  const title = summary['title'];
  if (typeof title !== 'string' || title.trim().length === 0 || title.length > 200) invalid('title');
  let startingSituation: StartingSituation | null = null;
  const situation = source['startingSituation'];
  if (situation !== null && situation !== undefined) {
    const row = record(situation, 'startingSituation');
    const locale = row['locale'];
    const text = row['text'];
    if (locale !== 'en' && locale !== 'it') invalid('locale');
    if (typeof text !== 'string' || text.trim().length === 0 || text.length > 8000) invalid('text');
    startingSituation = { text: text as string, locale: locale as 'en' | 'it', fallback: flag(row, 'fallback') };
  }
  return { adventureId, campaignId, title: title as string, stateRevision: count(scope, 'stateRevision'), startingSituation };
}

/** Pending decision kinds of the resume card; the stored decision payload is never published. */
export const DECISION_KINDS = ['STARTING_SITUATION', 'FREE_INPUT', 'OTHER'] as const;
export type DecisionKind = (typeof DECISION_KINDS)[number];
/** Pinned-content diagnosis statuses and issue codes (docs/08 section 10). */
export const DEPENDENCY_STATUSES = ['AVAILABLE', 'UNSEALED', 'MISSING'] as const;
export const DEPENDENCY_CODES = ['REVISION_MISSING', 'RULESET_UNAVAILABLE', 'CONTENT_CORRUPT', 'DEPENDENCY_MISSING', 'CLOSURE_INVALID'] as const;
export type DependencyCode = (typeof DEPENDENCY_CODES)[number];
/** Most entries the server lists per resume-card collection; larger lists are refused as invalid. */
const MAX_CARD_ROWS = 1000;
/** Most actions awaiting narration the server lists; the total is reported separately. */
const MAX_AWAITING = 16;

/** One missing or unusable pinned pack, named by its public coordinates. */
export interface DependencyIssue { readonly code: DependencyCode; readonly packId: string | null; readonly packVersion: string | null }

/** Presentation of a committed action whose narration is not published yet. */
export interface AwaitingNarration {
  readonly operationId: string;
  readonly commandType: string;
  readonly status: OperationStatus;
  readonly committedRevision: number;
  readonly receipt: PublicReceipt | null;
  /** IN_PROGRESS resumes by itself; RECEIPT means the saved receipt is the presentation. */
  readonly mode: 'IN_PROGRESS' | 'RECEIPT';
  readonly reason: string | null;
  readonly retriesRemaining: number;
}

/**
 * The short factual resume card of a playthrough (docs/08 section 10, docs/13): where play stands
 * at one committed revision. Reading it never advances time; the interface only displays it.
 */
export interface ResumeCard {
  readonly stateRevision: number;
  readonly location: { readonly id: string; readonly name: string | null; readonly nameLocale: 'en' | 'it' | null; readonly nameFallback: boolean } | null;
  readonly presentActors: readonly { readonly actorId: string; readonly name: string }[];
  readonly resources: readonly { readonly key: string; readonly minimum: number; readonly current: number; readonly maximum: number }[];
  readonly pendingDecision: DecisionKind;
  readonly awaitingNarrationTotal: number;
  readonly awaitingNarration: readonly AwaitingNarration[];
  readonly dependencyStatus: (typeof DEPENDENCY_STATUSES)[number];
  readonly mutationPaused: boolean;
  readonly issues: readonly DependencyIssue[];
}

/** Read one value of a closed enumeration. */
function choice<T extends string>(source: Record<string, unknown>, field: string, allowed: readonly T[]): T {
  const value = source[field];
  return allowed.includes(value as T) ? value as T : invalid(field);
}

/** Read a nullable bounded token. */
function optionalToken(source: Record<string, unknown>, field: string, max = 120): string | null {
  return source[field] === null || source[field] === undefined ? null : token(source, field, max);
}

/**
 * Validate a resume card body against the route's scope.
 *
 * @param value decoded JSON body of the resume endpoint
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @throws CampaignProtocolError `invalid` for a foreign scope, an unknown enumeration or an oversized list
 */
export function parseResumeCard(value: unknown, adventureId: string, campaignId: string): ResumeCard {
  const source = record(value, 'resume');
  const scope = record(source['scope'], 'scope');
  if (uuid(scope, 'adventureId') !== adventureId || uuid(scope, 'campaignId') !== campaignId) invalid('scope');
  let location: ResumeCard['location'] = null;
  if (source['location'] !== null && source['location'] !== undefined) {
    const row = record(source['location'], 'location');
    const name = optionalToken(row, 'name', 200);
    const nameLocale = row['nameLocale'] === null || row['nameLocale'] === undefined ? null : choice(row, 'nameLocale', ['en', 'it'] as const);
    location = { id: token(row, 'id', 200), name, nameLocale, nameFallback: flag(row, 'nameFallback') };
  }
  const dependencies = record(source['dependencies'], 'dependencies');
  const total = count(source, 'awaitingNarrationTotal');
  const awaiting = list(source, 'awaitingNarration', MAX_AWAITING).map(/** One owed narration with its recap. */ (entry) => {
    const row = record(entry, 'awaitingNarration');
    const narration = record(row['narration'], 'narration');
    const reason = narration['reason'];
    if (reason !== null && (typeof reason !== 'string' || !(NARRATION_REASONS as readonly string[]).includes(reason))) invalid('reason');
    return {
      operationId: uuid(row, 'operationId'), commandType: token(row, 'commandType'), status: status(row, 'status'),
      committedRevision: count(row, 'committedRevision'),
      receipt: row['receipt'] === null || row['receipt'] === undefined ? null : parseReceipt(row['receipt']),
      mode: choice(narration, 'mode', ['IN_PROGRESS', 'RECEIPT'] as const), reason: reason as string | null,
      retriesRemaining: count(narration, 'retriesRemaining'),
    };
  });
  if (awaiting.length > total) invalid('awaitingNarrationTotal');
  return {
    stateRevision: count(scope, 'stateRevision'),
    location,
    presentActors: list(source, 'presentActors', MAX_CARD_ROWS).map(/** Known co-present actors by name. */ (entry) => {
      const row = record(entry, 'presentActors');
      return { actorId: uuid(row, 'actorId'), name: token(row, 'name', 200) };
    }),
    resources: list(source, 'resources', MAX_CARD_ROWS).map(/** Exact values; keys stay literal identifiers. */ (entry) => {
      const row = record(entry, 'resources');
      return { key: token(row, 'key'), minimum: integer(row, 'minimum'), current: integer(row, 'current'), maximum: integer(row, 'maximum') };
    }),
    pendingDecision: choice(source, 'pendingDecision', DECISION_KINDS),
    awaitingNarrationTotal: total,
    awaitingNarration: awaiting,
    dependencyStatus: choice(dependencies, 'status', DEPENDENCY_STATUSES),
    mutationPaused: flag(dependencies, 'mutationPaused'),
    issues: list(dependencies, 'issues', 64).map(/** Fixed codes with public pack coordinates. */ (entry) => {
      const row = record(entry, 'issues');
      return { code: choice(row, 'code', DEPENDENCY_CODES), packId: optionalToken(row, 'packId', 200), packVersion: optionalToken(row, 'packVersion', 64) };
    }),
  };
}

/** Most rows the server lists per panel collection; larger lists are refused as invalid. */
const MAX_PANEL_ROWS = 1000;
/** Most journal entries one page can hold, and most discoveries or promises listed. */
const MAX_JOURNAL_PAGE = 100;
const MAX_KNOWLEDGE_ROWS = 100;
/** Quest statuses and outcome kinds the panels know (docs/10 section 5). */
export const QUEST_STATUSES = ['available', 'active', 'suspended', 'completed', 'failed'] as const;
export type QuestStatusName = (typeof QUEST_STATUSES)[number];
export const OUTCOME_KINDS = ['success', 'failure', 'alternative'] as const;
export const STANCES = ['KNOWS', 'BELIEVES', 'RUMORED', 'SUSPECTS', 'DOUBTS', 'COMMON'] as const;
export const PROMISE_KINDS = ['PROMISE', 'OBLIGATION', 'AGREEMENT'] as const;
export const PROMISE_DIRECTIONS = ['YOU_OWE', 'OWED_TO_YOU', 'BETWEEN_OTHERS'] as const;

/** Public pack text with its actual language and whether the source language replaced a missing overlay. */
export interface PanelText { readonly text: string; readonly locale: 'en' | 'it'; readonly fallback: boolean }
/** The in-world clock: total seconds and the calendar reading the server computed. */
export interface PanelClock { readonly seconds: number; readonly day: number; readonly hour: number; readonly minute: number; readonly second: number }
export interface PanelAttribute { readonly key: string; readonly name: PanelText | null; readonly base: number; readonly effective: number }
export interface PanelSkill { readonly key: string; readonly name: PanelText | null; readonly rank: number }
export interface PanelCharacter {
  readonly actorId: string;
  readonly name: string;
  readonly role: string | null;
  readonly location: { readonly id: string; readonly name: PanelText | null } | null;
  readonly attributes: readonly PanelAttribute[];
  readonly skills: readonly PanelSkill[];
  readonly derived: readonly { readonly key: string; readonly value: number }[];
}
export interface PanelResource { readonly key: string; readonly name: PanelText | null; readonly minimum: number; readonly current: number; readonly maximum: number }
export interface PanelCondition { readonly id: string; readonly name: PanelText | null; readonly stacks: number; readonly remainingRounds: number }
export interface PanelAbility { readonly id: string; readonly name: PanelText | null; readonly rank: number }
export interface PanelItem {
  readonly itemId: string;
  readonly definitionId: string;
  readonly name: PanelText | null;
  readonly quantity: number;
  readonly rarity: string | null;
  readonly rarityName: PanelText | null;
  readonly wornIn: string | null;
}
export interface PanelWorn { readonly itemId: string; readonly definitionId: string; readonly name: PanelText | null; readonly slot: string; readonly hands: number }
export interface PanelObjective { readonly id: string; readonly description: PanelText | null; readonly optional: boolean; readonly satisfied: boolean }
export interface PanelReward { readonly itemId: string; readonly name: PanelText | null; readonly quantity: number }
export interface PanelOutcome { readonly id: string; readonly kind: (typeof OUTCOME_KINDS)[number]; readonly description: PanelText | null; readonly rewards: readonly PanelReward[] }
export interface PanelQuest {
  readonly id: string;
  readonly name: PanelText | null;
  readonly summary: PanelText | null;
  readonly status: QuestStatusName;
  readonly updatedRevision: number;
  readonly objectives: readonly PanelObjective[];
  readonly outcome: PanelOutcome | null;
}
export interface PanelDiscovery {
  readonly claimId: string;
  readonly stance: (typeof STANCES)[number];
  readonly acquisition: string | null;
  readonly statement: string;
  readonly statementLocale: 'en' | 'it';
  readonly learnedRevision: number;
}
export interface PanelPromise {
  readonly commitmentId: string;
  readonly kind: (typeof PROMISE_KINDS)[number];
  readonly direction: (typeof PROMISE_DIRECTIONS)[number];
  readonly debtor: string | null;
  readonly creditor: string | null;
  readonly terms: string;
  readonly termsLocale: 'en' | 'it';
  readonly dueWorldTime: number | null;
  readonly overdue: boolean;
}
/** One worded public event; the statement is in the campaign's narrative language. */
export interface JournalEntry { readonly sequence: number; readonly type: string; readonly stateRevision: number; readonly clock: PanelClock; readonly statement: string }

/**
 * The player panels and journal at one committed revision (P10.5, docs/13, "P10.5 panels and journal"). Everything here is
 * display data the server decided: the interface never derives a bonus, a restoration or a quest result.
 */
export interface PlayPanels {
  readonly stateRevision: number;
  readonly locale: 'en' | 'it';
  readonly contentAvailable: boolean;
  readonly clock: PanelClock;
  readonly character: PanelCharacter | null;
  readonly resources: readonly PanelResource[];
  readonly conditions: readonly PanelCondition[];
  readonly abilities: readonly PanelAbility[];
  readonly inventory: readonly PanelItem[];
  readonly equipment: readonly PanelWorn[];
  readonly quests: readonly PanelQuest[];
  readonly discoveriesTotal: number;
  readonly discoveries: readonly PanelDiscovery[];
  readonly promisesTotal: number;
  readonly promises: readonly PanelPromise[];
  readonly journal: readonly JournalEntry[];
  /** Cursor for the next older journal page, or null when the oldest event is listed. */
  readonly nextBefore: number | null;
}

/** Read an optional pack text (`null` when the pack has none). */
function panelText(source: Record<string, unknown>, field: string): PanelText | null {
  const value = source[field];
  if (value === null || value === undefined) return null;
  const row = record(value, field);
  const text = row['text'];
  if (typeof text !== 'string' || text.trim().length === 0 || text.length > 4000) invalid(field);
  return { text: text as string, locale: choice(row, 'locale', ['en', 'it'] as const), fallback: flag(row, 'fallback') };
}

/** Read a statement-like free text field bounded to 2000 characters. */
function statement(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > 2000) invalid(field);
  return value as string;
}

/** Read a calendar reading and refuse an impossible one. */
function panelClock(value: unknown, field: string): PanelClock {
  const row = record(value, field);
  const clock = { seconds: count(row, 'seconds'), day: count(row, 'day'), hour: count(row, 'hour'), minute: count(row, 'minute'), second: count(row, 'second') };
  if (clock.hour > 23 || clock.minute > 59 || clock.second > 59) invalid(field);
  return clock;
}

/**
 * Validate a panels body against the route's scope.
 *
 * @param value decoded JSON body of the panels endpoint
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @throws CampaignProtocolError `invalid` for a foreign scope, an unknown enumeration or an oversized list
 */
export function parsePlayPanels(value: unknown, adventureId: string, campaignId: string): PlayPanels {
  const source = record(value, 'panels');
  const scope = record(source['scope'], 'scope');
  if (uuid(scope, 'adventureId') !== adventureId || uuid(scope, 'campaignId') !== campaignId) invalid('scope');
  const rows = (field: string, max = MAX_PANEL_ROWS): Record<string, unknown>[] => list(source, field, max).map((entry) => record(entry, field));
  let character: PanelCharacter | null = null;
  if (source['character'] !== null && source['character'] !== undefined) {
    const row = record(source['character'], 'character');
    const place = row['location'] === null || row['location'] === undefined ? null : record(row['location'], 'location');
    character = {
      actorId: uuid(row, 'actorId'), name: token(row, 'name', 200), role: optionalToken(row, 'role', 200),
      location: place ? { id: token(place, 'id', 200), name: panelText(place, 'name') } : null,
      attributes: list(row, 'attributes', MAX_PANEL_ROWS).map((entry) => {
        const item = record(entry, 'attributes');
        return { key: token(item, 'key'), name: panelText(item, 'name'), base: integer(item, 'base'), effective: integer(item, 'effective') };
      }),
      skills: list(row, 'skills', MAX_PANEL_ROWS).map((entry) => {
        const item = record(entry, 'skills');
        return { key: token(item, 'key'), name: panelText(item, 'name'), rank: count(item, 'rank') };
      }),
      derived: list(row, 'derived', MAX_PANEL_ROWS).map((entry) => {
        const item = record(entry, 'derived');
        return { key: token(item, 'key'), value: integer(item, 'value') };
      }),
    };
  }
  const knowledge = record(source['knowledge'], 'knowledge');
  const journal = record(source['journal'], 'journal');
  const next = journal['nextBefore'];
  if (next !== null && next !== undefined && !(typeof next === 'number' && Number.isSafeInteger(next) && next > 0)) invalid('nextBefore');
  const entries = list(journal, 'entries', MAX_JOURNAL_PAGE).map((entry): JournalEntry => {
    const row = record(entry, 'entries');
    return { sequence: count(row, 'sequence'), type: token(row, 'type', 128), stateRevision: count(row, 'stateRevision'),
      clock: panelClock(row['clock'], 'clock'), statement: statement(row, 'statement') };
  });
  const discoveries = list(knowledge, 'discoveries', MAX_KNOWLEDGE_ROWS).map((entry): PanelDiscovery => {
    const row = record(entry, 'discoveries');
    return { claimId: uuid(row, 'claimId'), stance: choice(row, 'stance', STANCES), acquisition: optionalToken(row, 'acquisition', 32),
      statement: statement(row, 'statement'), statementLocale: choice(row, 'statementLocale', ['en', 'it'] as const),
      learnedRevision: count(row, 'learnedRevision') };
  });
  const promises = list(knowledge, 'promises', MAX_KNOWLEDGE_ROWS).map((entry): PanelPromise => {
    const row = record(entry, 'promises');
    const due = row['dueWorldTime'];
    if (due !== null && due !== undefined && !(typeof due === 'number' && Number.isSafeInteger(due) && due >= 0)) invalid('dueWorldTime');
    return { commitmentId: uuid(row, 'commitmentId'), kind: choice(row, 'kind', PROMISE_KINDS), direction: choice(row, 'direction', PROMISE_DIRECTIONS),
      debtor: optionalToken(row, 'debtor', 200), creditor: optionalToken(row, 'creditor', 200), terms: statement(row, 'terms'),
      termsLocale: choice(row, 'termsLocale', ['en', 'it'] as const), dueWorldTime: (due as number | null | undefined) ?? null, overdue: flag(row, 'overdue') };
  });
  const discoveriesTotal = count(knowledge, 'discoveriesTotal');
  const promisesTotal = count(knowledge, 'promisesTotal');
  if (discoveries.length > discoveriesTotal || promises.length > promisesTotal) invalid('knowledge');
  return {
    stateRevision: count(scope, 'stateRevision'),
    locale: choice(source, 'locale', ['en', 'it'] as const),
    contentAvailable: flag(source, 'contentAvailable'),
    clock: panelClock(source['clock'], 'clock'),
    character,
    resources: rows('resources').map((row) => ({ key: token(row, 'key'), name: panelText(row, 'name'), minimum: integer(row, 'minimum'),
      current: integer(row, 'current'), maximum: integer(row, 'maximum') })),
    conditions: rows('conditions').map((row) => ({ id: token(row, 'id'), name: panelText(row, 'name'), stacks: count(row, 'stacks'),
      remainingRounds: count(row, 'remainingRounds') })),
    abilities: rows('abilities').map((row) => ({ id: token(row, 'id'), name: panelText(row, 'name'), rank: count(row, 'rank') })),
    inventory: rows('inventory').map((row) => ({ itemId: uuid(row, 'itemId'), definitionId: token(row, 'definitionId', 200),
      name: panelText(row, 'name'), quantity: count(row, 'quantity'), rarity: optionalToken(row, 'rarity', 200),
      rarityName: panelText(row, 'rarityName'), wornIn: optionalToken(row, 'wornIn', 200) })),
    equipment: rows('equipment').map((row) => ({ itemId: uuid(row, 'itemId'), definitionId: token(row, 'definitionId', 200),
      name: panelText(row, 'name'), slot: token(row, 'slot', 200), hands: count(row, 'hands') })),
    quests: rows('quests').map((row): PanelQuest => {
      const outcome = row['outcome'] === null || row['outcome'] === undefined ? null : record(row['outcome'], 'outcome');
      return {
        id: token(row, 'id', 260), name: panelText(row, 'name'), summary: panelText(row, 'summary'),
        status: choice(row, 'status', QUEST_STATUSES), updatedRevision: count(row, 'updatedRevision'),
        objectives: list(row, 'objectives', 32).map((entry) => {
          const item = record(entry, 'objectives');
          return { id: token(item, 'id', 260), description: panelText(item, 'description'), optional: flag(item, 'optional'), satisfied: flag(item, 'satisfied') };
        }),
        outcome: outcome ? {
          id: token(outcome, 'id', 260), kind: choice(outcome, 'kind', OUTCOME_KINDS), description: panelText(outcome, 'description'),
          rewards: list(outcome, 'rewards', 8).map((entry) => {
            const item = record(entry, 'rewards');
            return { itemId: token(item, 'itemId', 260), name: panelText(item, 'name'), quantity: count(item, 'quantity') };
          }),
        } : null,
      };
    }),
    discoveriesTotal, discoveries, promisesTotal, promises,
    journal: entries,
    nextBefore: (next as number | null | undefined) ?? null,
  };
}
