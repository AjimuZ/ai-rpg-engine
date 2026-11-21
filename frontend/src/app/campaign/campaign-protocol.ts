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
  /** How the committed action is presented (P08.5); null before the commit, absent only on views built outside the parser. */
  readonly presentation?: OperationPresentation | null;
}

/** Presentation modes of a committed operation: narration in progress, published narration, or the receipt fallback. */
export const PRESENTATION_MODES = ['IN_PROGRESS', 'NARRATED', 'RECEIPT'] as const;
/** Most passages and suggestions one narration may carry; the server cuts both lists lower, so a larger list is refused. */
export const MAX_NARRATION_PASSAGES = 24;
export const MAX_NARRATION_SUGGESTIONS = 3;
/** Longest passage text, label and draft the browser accepts; longer text is refused, never cut. */
const MAX_PASSAGE_CHARS = 8_000;

/** One attributed passage of published narration; `speaker` is null for the narrator. */
export interface NarrationPassage {
  readonly messageId: string;
  readonly kind: 'NARRATION' | 'CHARACTER_SPEECH';
  readonly speaker: { readonly actorId: string; readonly name: string } | null;
  readonly text: string;
  readonly locale: 'en' | 'it';
}

/** One optional quick reply; choosing it only fills the composer, and `actionId` is never sent by the browser. */
export interface NarrationSuggestion {
  readonly suggestionId: string;
  readonly kind: 'REPLY' | 'QUESTION' | 'ACTION';
  readonly label: string;
  readonly draftText: string;
  readonly actionId: string | null;
}

/** The player-safe presentation of one committed operation. Passages and suggestions are empty unless `mode` is `NARRATED`. */
export interface OperationPresentation {
  readonly mode: (typeof PRESENTATION_MODES)[number];
  readonly reason: string | null;
  readonly retriesRemaining: number;
  readonly passages: readonly NarrationPassage[];
  readonly suggestions: readonly NarrationSuggestion[];
}

/** Read bounded display text: non-blank, within {@link MAX_PASSAGE_CHARS}, never trimmed or reinterpreted. */
function shownText(source: Record<string, unknown>, field: string, max = MAX_PASSAGE_CHARS): string {
  const value = source[field];
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) invalid(field);
  return value as string;
}

/** Validate the `presentation` member of an operation body; null and a missing member both mean "not presented yet". */
function parsePresentation(value: unknown): OperationPresentation | null {
  if (value === null || value === undefined) return null;
  const source = record(value, 'presentation');
  const mode = choice(source, 'mode', PRESENTATION_MODES);
  const reason = source['reason'];
  if (reason !== null && reason !== undefined && (typeof reason !== 'string' || !(NARRATION_REASONS as readonly string[]).includes(reason))) invalid('reason');
  const narration = source['narration'];
  if (mode !== 'NARRATED' || narration === null || narration === undefined) {
    return { mode, reason: (reason as string | null | undefined) ?? null, retriesRemaining: count(source, 'retriesRemaining'), passages: [], suggestions: [] };
  }
  const body = record(narration, 'narration');
  choice(body, 'locale', ['en', 'it'] as const);
  const passages = list(body, 'passages', MAX_NARRATION_PASSAGES).map((entry): NarrationPassage => {
    const row = record(entry, 'passage');
    const speaker = row['speaker'];
    const named = speaker === null || speaker === undefined ? null : record(speaker, 'speaker');
    const kind = choice(row, 'kind', ['NARRATION', 'CHARACTER_SPEECH'] as const);
    // Speech without a named speaker could not be attributed, so it is refused instead of shown as anonymous words.
    if ((kind === 'CHARACTER_SPEECH') !== (named !== null)) invalid('speaker');
    return { messageId: token(row, 'messageId', 80), kind, text: shownText(row, 'text'), locale: choice(row, 'locale', ['en', 'it'] as const),
      speaker: named === null ? null : { actorId: uuid(named, 'actorId'), name: token(named, 'name', 200) } };
  });
  const suggestions = list(body, 'suggestions', MAX_NARRATION_SUGGESTIONS).map((entry): NarrationSuggestion => {
    const row = record(entry, 'suggestion');
    const action = row['actionId'];
    if (action !== null && action !== undefined && typeof action !== 'string') invalid('actionId');
    return { suggestionId: token(row, 'suggestionId', 80), kind: choice(row, 'kind', ['REPLY', 'QUESTION', 'ACTION'] as const),
      label: shownText(row, 'label', 240), draftText: shownText(row, 'draftText', 500), actionId: (action as string | null | undefined) ?? null };
  });
  if (new Set(suggestions.map((suggestion) => suggestion.suggestionId)).size !== suggestions.length) invalid('suggestionId');
  return { mode, reason: null, retriesRemaining: 0, passages, suggestions };
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
    presentation: parsePresentation(source['presentation']),
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
  /**
   * True while a fight is active; only then does the page read the combat projection by itself (P12.1-B). Outside a fight it reads
   * the projection's offers only when the player asks (P13.2-B).
   */
  readonly inFight: boolean;
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
    inFight: source['encounter'] !== null && source['encounter'] !== undefined,
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

/** The seven separate artwork categories of the asset registry (docs/23 section 3). */
export const ARTWORK_CATEGORIES = ['scene', 'map', 'portrait', 'item', 'ui', 'music', 'effect'] as const;
export type ArtworkCategory = (typeof ARTWORK_CATEGORIES)[number];
/** Most artwork entries one listing can hold; the server cuts its listing at the same number. */
export const MAX_ARTWORK_ROWS = 500;

/** One accepted asset in force for a subject: what a browser needs to show it and to credit it. */
export interface ArtworkEntry {
  readonly category: ArtworkCategory;
  /** Stable public reference (an item definition, character or scene ID), never a display name. */
  readonly subject: string;
  readonly appearanceRevision: number;
  /** Lower-case SHA-256, the identity the hash route serves. */
  readonly sha256: string;
  readonly mediaType: string;
  readonly width: number;
  readonly height: number;
  readonly credit: string;
}

/**
 * Validate the artwork listing of one campaign (`GET /api/v1/campaigns/{id}/artwork`).
 *
 * @param value decoded JSON body
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @returns every entry, in the server's order
 * @throws CampaignProtocolError `invalid` for a foreign scope, an unknown category, a malformed hash or an oversized list
 */
export function parseArtwork(value: unknown, adventureId: string, campaignId: string): readonly ArtworkEntry[] {
  const source = record(value, 'artwork');
  const scope = record(source['scope'], 'scope');
  if (uuid(scope, 'adventureId') !== adventureId || uuid(scope, 'campaignId') !== campaignId) invalid('scope');
  return list(source, 'artwork', MAX_ARTWORK_ROWS).map((entry): ArtworkEntry => {
    const row = record(entry, 'artwork');
    const sha256 = token(row, 'sha256', 64);
    if (!/^[0-9a-f]{64}$/.test(sha256)) invalid('sha256');
    const mediaType = choice(row, 'mediaType', ['image/png', 'image/jpeg', 'image/webp', 'audio/ogg', 'audio/mpeg', 'audio/wav'] as const);
    return { category: choice(row, 'category', ARTWORK_CATEGORIES), subject: token(row, 'subject', 64),
      appearanceRevision: count(row, 'appearanceRevision'), sha256, mediaType,
      width: count(row, 'width'), height: count(row, 'height'), credit: token(row, 'credit', 200) };
  });
}

/** Lifecycle phases, sides, standings and objective states of the combat projection (docs/11, docs/13 "P12.1-A"). */
export const COMBAT_PHASES = ['PROPOSED', 'VALIDATED', 'REJECTED', 'ENTERING', 'ROUND_OPEN', 'ACTOR_START', 'AWAITING_ACTION', 'RESOLVING',
  'ACTOR_END', 'ROUND_END', 'OUTCOME_PENDING', 'SETTLED', 'NARRATIVE_RESUME'] as const;
export const COMBAT_SIDES = ['player', 'ally', 'hostile'] as const;
export const COMBAT_STATUSES = ['ACTIVE', 'DEFEATED', 'ESCAPED', 'SURRENDERED'] as const;
export const COMBAT_COVER = ['none', 'partial', 'full'] as const;
export const COMBAT_OBJECTIVE_KINDS = ['defeatHostiles', 'escape', 'reachZone', 'survive', 'protect'] as const;
export const COMBAT_OBJECTIVE_STATUSES = ['pending', 'met', 'failed'] as const;
export const COMBAT_OUTCOMES = ['VICTORY', 'DEFEAT', 'ESCAPE', 'OBJECTIVE', 'PLAYER_SURRENDER', 'HOSTILE_SURRENDER', 'AGREEMENT', 'STALEMATE'] as const;
export const COMBAT_ACTION_KINDS = ['social', 'scene'] as const;
export const COMBAT_REACTION_MODES = ['off', 'first', 'healthAtMost', 'focusAtLeast'] as const;
/** Most rows of any list of the projection; a larger list is refused as invalid. */
const MAX_COMBAT_ROWS = 256;

export interface CombatInitiative { readonly rank: number; readonly participantId: string; readonly initiative: number }
export interface CombatZone { readonly id: string; readonly cover: (typeof COMBAT_COVER)[number]; readonly obscured: boolean; readonly capacity: number }
/** A directed edge; `cost` is null while the passage is blocked. */
export interface CombatEdge { readonly from: string; readonly to: string; readonly difficult: boolean; readonly blocked: boolean; readonly cost: number | null }
export interface CombatEffect { readonly effectId: string; readonly stacks: number }
/** What the player learned by assessing a participant, with the round and revision it was learned at. */
export interface CombatKnown {
  readonly health: number; readonly healthMax: number; readonly guard: number; readonly resistance: number; readonly armor: number;
  readonly round: number; readonly stateRevision: number;
}
export interface CombatParticipant {
  readonly participantId: string;
  readonly side: (typeof COMBAT_SIDES)[number];
  readonly zone: string;
  readonly status: (typeof COMBAT_STATUSES)[number];
  readonly effects: readonly CombatEffect[];
  readonly known: CombatKnown | null;
}
export interface CombatPlayer {
  readonly health: number; readonly healthMax: number; readonly focus: number; readonly focusMax: number; readonly guard: number;
  readonly resistance: number; readonly armor: number; readonly guarding: boolean; readonly reactionsLeft: number;
}
export interface CombatObjective {
  readonly objectiveId: string; readonly kind: (typeof COMBAT_OBJECTIVE_KINDS)[number]; readonly zone: string | null;
  readonly participantId: string | null; readonly rounds: number | null; readonly status: (typeof COMBAT_OBJECTIVE_STATUSES)[number];
}
export interface CombatCost { readonly cost: number; readonly available: boolean }
export interface CombatTerms { readonly termsId: string; readonly kind: string; readonly cost: number }
export interface CombatActionOption {
  readonly actionId: string; readonly kind: (typeof COMBAT_ACTION_KINDS)[number]; readonly cost: number; readonly targets: readonly string[];
  readonly check: { readonly attribute: string; readonly skill: string | null; readonly rank: number } | null;
  readonly terms: CombatTerms | null;
}
export interface CombatStrikeOption { readonly attackId: string; readonly cost: number; readonly targets: readonly string[]; readonly disabled: string | null }
export interface CombatAbilityOption {
  readonly abilityId: string; readonly cost: number; readonly focus: number; readonly targets: readonly string[]; readonly maxTargets: number;
  readonly disabled: string | null;
}
export interface CombatReactionOption { readonly reactionId: string; readonly mode: (typeof COMBAT_REACTION_MODES)[number]; readonly value: number }
export interface CombatOffer {
  readonly offerId: string; readonly actionId: string; readonly recipientId: string; readonly response: string; readonly terms: CombatTerms;
  readonly canAccept: boolean;
}
/** The player's legal candidates with costs, targets and stable disabled reasons, as a tactical receipt lists them. */
export interface CombatOptions {
  readonly moves: readonly { readonly zone: string; readonly cost: number }[];
  readonly speechRemaining: number;
  readonly speechRecipients: readonly string[];
  readonly actions: readonly CombatActionOption[];
  readonly strikes: readonly CombatStrikeOption[];
  readonly guard: CombatCost | null;
  readonly useItem: CombatCost | null;
  readonly abilities: readonly CombatAbilityOption[];
  readonly reactions: readonly CombatReactionOption[];
  readonly ready: CombatCost | null;
  readonly assess: { readonly cost: number; readonly targets: readonly string[] } | null;
  readonly withdraw: readonly { readonly zone: string; readonly cost: number }[];
  readonly endActivation: boolean;
  readonly offer: CombatOffer | null;
}
/** The verdict waiting for the player's explicit settlement. */
export interface CombatVerdict {
  readonly outcome: (typeof COMBAT_OUTCOMES)[number]; readonly objectiveId: string | null; readonly termsId: string | null;
  readonly roundCap: boolean; readonly objectives: readonly { readonly objectiveId: string; readonly status: (typeof COMBAT_OBJECTIVE_STATUSES)[number] }[];
}
export interface CombatEncounter {
  readonly encounterId: string;
  readonly phase: (typeof COMBAT_PHASES)[number];
  readonly round: number;
  readonly roundCap: number;
  readonly acting: string | null;
  readonly actionPoints: number | null;
  readonly actionPointsPerActivation: number;
  readonly playerDecision: boolean;
  readonly initiative: readonly CombatInitiative[];
  readonly zones: readonly CombatZone[];
  readonly edges: readonly CombatEdge[];
  readonly escapeZones: readonly string[];
  readonly participants: readonly CombatParticipant[];
  readonly player: CombatPlayer;
  readonly objectives: readonly CombatObjective[];
  readonly options: CombatOptions | null;
  readonly outcome: CombatVerdict | null;
}
/** Opening-action policies of an encounter the player may start (docs/11 section 3). */
export const COMBAT_OPENING_POLICIES = ['initiative', 'first', 'forbidden'] as const;
/** One observable participant of an offered encounter: never the player, never a statistic. */
export interface EncounterOfferParticipant { readonly participantId: string; readonly side: 'ally' | 'hostile'; readonly zone: string }
/** One objective of an offered encounter, before the fight gives it a status. */
export type EncounterStake = Omit<CombatObjective, 'status'>;
/**
 * One encounter the player may start now with `ENGAGE` (P13.2-B, ADR 0042), holding only what entry reveals: who is observably
 * there and where, where the player starts, the escape zones, the stakes and the opening policy with its lawful opening targets.
 */
export interface EncounterOffer {
  readonly templateId: string;
  readonly playerZone: string;
  readonly participants: readonly EncounterOfferParticipant[];
  readonly escapeZones: readonly string[];
  readonly objectives: readonly EncounterStake[];
  readonly openingPolicy: (typeof COMBAT_OPENING_POLICIES)[number];
  readonly openingTargets: readonly string[];
}
/**
 * The combat projection at one committed revision; `encounter` is null outside a fight, and `offers` names the encounters the player
 * may start now. Offers are always empty during a fight.
 */
export interface CombatView { readonly stateRevision: number; readonly encounter: CombatEncounter | null; readonly offers: readonly EncounterOffer[] }

/** Read an array of bounded identifiers. */
function identifiers(source: Record<string, unknown>, field: string): string[] {
  return list(source, field, MAX_COMBAT_ROWS).map((entry) => typeof entry === 'string' && entry.length > 0 && entry.length <= 200 ? entry : invalid(field));
}

/** Read a nullable stable reason code; only uppercase words are accepted, and display text never comes from it. */
function reason(source: Record<string, unknown>, field: string): string | null {
  const value = source[field];
  if (value === null || value === undefined) return null;
  return typeof value === 'string' && /^[A-Z][A-Z_]{1,40}$/.test(value) ? value : invalid(field);
}

/** Read a nullable integer. */
function optionalCount(source: Record<string, unknown>, field: string): number | null {
  return source[field] === null || source[field] === undefined ? null : count(source, field);
}

/** Read rows of objects. */
function rowsOf(source: Record<string, unknown>, field: string): Record<string, unknown>[] {
  return list(source, field, MAX_COMBAT_ROWS).map((entry) => record(entry, field));
}

/** Read declared terms: identity, kind and cost. */
function terms(value: unknown): CombatTerms {
  const row = record(value, 'terms');
  return { termsId: token(row, 'termsId', 200), kind: token(row, 'kind', 64), cost: count(row, 'cost') };
}

/** Read an optional `{cost, available}` member. */
function priced(source: Record<string, unknown>, field: string): CombatCost | null {
  if (source[field] === undefined) return null;
  const row = record(source[field], field);
  return { cost: count(row, 'cost'), available: flag(row, 'available') };
}

/** Read the options a receipt lists; an empty object means the player has no decision now. */
function parseCombatOptions(value: unknown): CombatOptions | null {
  const source = record(value, 'options');
  if (Object.keys(source).length === 0) return null;
  const speech = record(source['speech'], 'speech');
  const assess = source['assess'] === undefined ? null : record(source['assess'], 'assess');
  const offer = source['offer'] === undefined ? null : record(source['offer'], 'offer');
  return {
    moves: rowsOf(source, 'moves').map((row) => ({ zone: token(row, 'zone', 200), cost: count(row, 'cost') })),
    speechRemaining: count(speech, 'remaining'),
    speechRecipients: identifiers(speech, 'recipients'),
    actions: rowsOf(source, 'actions').map((row): CombatActionOption => {
      const check = row['check'] === undefined ? null : record(row['check'], 'check');
      return {
        actionId: token(row, 'actionId', 200), kind: choice(row, 'kind', COMBAT_ACTION_KINDS), cost: count(row, 'cost'), targets: identifiers(row, 'targets'),
        check: check ? { attribute: token(check, 'attribute', 200), skill: optionalToken(check, 'skill', 200), rank: count(check, 'rank') } : null,
        terms: row['terms'] === undefined ? null : terms(row['terms']),
      };
    }),
    strikes: (source['strikes'] === undefined ? [] : rowsOf(source, 'strikes')).map((row) => ({
      attackId: token(row, 'attackId', 200), cost: count(row, 'cost'), targets: identifiers(row, 'targets'), disabled: reason(row, 'disabled') })),
    guard: priced(source, 'guard'),
    useItem: priced(source, 'useItem'),
    abilities: (source['abilities'] === undefined ? [] : rowsOf(source, 'abilities')).map((row) => ({
      abilityId: token(row, 'abilityId', 200), cost: count(row, 'cost'), focus: count(row, 'focus'), targets: identifiers(row, 'targets'),
      maxTargets: count(row, 'maxTargets'), disabled: reason(row, 'disabled') })),
    reactions: (source['reactions'] === undefined ? [] : rowsOf(source, 'reactions')).map((row) => {
      const policy = record(row['policy'], 'policy');
      return { reactionId: token(row, 'reactionId', 200), mode: choice(policy, 'mode', COMBAT_REACTION_MODES), value: count(policy, 'value') };
    }),
    ready: priced(source, 'ready'),
    assess: assess ? { cost: count(assess, 'cost'), targets: identifiers(assess, 'targets') } : null,
    withdraw: (source['withdraw'] === undefined ? [] : rowsOf(source, 'withdraw')).map((row) => ({ zone: token(row, 'zone', 200), cost: count(row, 'cost') })),
    endActivation: flag(source, 'endActivation'),
    offer: offer ? {
      offerId: token(offer, 'offerId', 200), actionId: token(offer, 'actionId', 200), recipientId: token(offer, 'recipientId', 200),
      response: token(offer, 'response', 64), terms: terms(offer['terms']), canAccept: flag(offer, 'canAccept'),
    } : null,
  };
}

/** Read the verdict at `OUTCOME_PENDING`. */
function parseCombatVerdict(value: unknown): CombatVerdict {
  const source = record(value, 'outcome');
  return {
    outcome: choice(source, 'outcome', COMBAT_OUTCOMES), objectiveId: optionalToken(source, 'objectiveId', 200),
    termsId: optionalToken(source, 'termsId', 200), roundCap: source['roundCap'] === true,
    objectives: rowsOf(source, 'objectives').map((row) => ({ objectiveId: token(row, 'objectiveId', 200), status: choice(row, 'status', COMBAT_OBJECTIVE_STATUSES) })),
  };
}

/**
 * Read the encounters offered outside a fight. An absent member is an empty list. An offer whose opening target is not one of its
 * hostile participants, or that names a target under the `forbidden` policy, is refused as invalid, so the page can never build an
 * `ENGAGE` the projection itself does not allow.
 */
function parseEncounterOffers(value: unknown): EncounterOffer[] {
  if (value === undefined || value === null) return [];
  return list({ offers: value }, 'offers', MAX_COMBAT_ROWS).map((entry): EncounterOffer => {
    const row = record(entry, 'offers');
    const participants = rowsOf(row, 'participants').map((who): EncounterOfferParticipant => ({
      participantId: token(who, 'participantId', 200), side: choice(who, 'side', ['ally', 'hostile'] as const), zone: token(who, 'zone', 200) }));
    const openingPolicy = choice(row, 'openingPolicy', COMBAT_OPENING_POLICIES);
    const openingTargets = identifiers(row, 'openingTargets');
    const hostile = new Set(participants.filter((who) => who.side === 'hostile').map((who) => who.participantId));
    if ((openingPolicy === 'forbidden' && openingTargets.length > 0) || !openingTargets.every((target) => hostile.has(target))) invalid('openingTargets');
    return {
      templateId: token(row, 'templateId', 256), playerZone: token(row, 'playerZone', 200), participants,
      escapeZones: identifiers(row, 'escapeZones'),
      objectives: rowsOf(row, 'objectives').map((stake) => ({
        objectiveId: token(stake, 'objectiveId', 200), kind: choice(stake, 'kind', COMBAT_OBJECTIVE_KINDS), zone: optionalToken(stake, 'zone', 200),
        participantId: optionalToken(stake, 'participantId', 200), rounds: optionalCount(stake, 'rounds') })),
      openingPolicy, openingTargets,
    };
  });
}

/**
 * Validate a combat projection body against the route's scope (docs/13 "P12.1-A combat projection", P13.2-B offers).
 *
 * @param value decoded JSON body of the encounter endpoint
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @throws CampaignProtocolError `invalid` for a foreign scope, an unknown enumeration, an oversized list, an offer during a fight,
 *         a repeated offer or an opening target the offer does not allow
 */
export function parseCombatView(value: unknown, adventureId: string, campaignId: string): CombatView {
  const source = record(value, 'encounter');
  const scope = record(source['scope'], 'scope');
  if (uuid(scope, 'adventureId') !== adventureId || uuid(scope, 'campaignId') !== campaignId) invalid('scope');
  const stateRevision = count(scope, 'stateRevision');
  const offers = parseEncounterOffers(source['offers']);
  if (new Set(offers.map((offer) => offer.templateId)).size !== offers.length) invalid('offers');
  if (source['encounter'] === null || source['encounter'] === undefined) return { stateRevision, encounter: null, offers };
  if (offers.length > 0) invalid('offers');
  const fight = record(source['encounter'], 'encounter');
  const player = record(fight['player'], 'player');
  const acting = optionalToken(fight, 'acting', 200);
  const actionPoints = optionalCount(fight, 'actionPoints');
  if ((acting === null) !== (actionPoints === null)) invalid('actionPoints');
  const playerDecision = flag(fight, 'playerDecision');
  const options = parseCombatOptions(fight['options'] ?? {});
  return {
    stateRevision,
    offers,
    encounter: {
      encounterId: uuid(fight, 'encounterId'), phase: choice(fight, 'phase', COMBAT_PHASES), round: count(fight, 'round'), roundCap: count(fight, 'roundCap'),
      acting, actionPoints, actionPointsPerActivation: count(fight, 'actionPointsPerActivation'), playerDecision,
      initiative: rowsOf(fight, 'initiative').map((row) => ({ rank: count(row, 'rank'), participantId: token(row, 'participantId', 200), initiative: integer(row, 'initiative') })),
      zones: rowsOf(fight, 'zones').map((row) => ({ id: token(row, 'id', 200), cover: choice(row, 'cover', COMBAT_COVER), obscured: flag(row, 'obscured'), capacity: count(row, 'capacity') })),
      edges: rowsOf(fight, 'edges').map((row) => {
        return { from: token(row, 'from', 200), to: token(row, 'to', 200), difficult: flag(row, 'difficult'), blocked: flag(row, 'blocked'), cost: optionalCount(row, 'cost') };
      }),
      escapeZones: identifiers(fight, 'escapeZones'),
      participants: rowsOf(fight, 'participants').map((row): CombatParticipant => {
        const known = row['known'] === null || row['known'] === undefined ? null : record(row['known'], 'known');
        return {
          participantId: token(row, 'participantId', 200), side: choice(row, 'side', COMBAT_SIDES), zone: token(row, 'zone', 200),
          status: choice(row, 'status', COMBAT_STATUSES),
          effects: rowsOf(row, 'effects').map((effect) => ({ effectId: token(effect, 'effectId', 200), stacks: count(effect, 'stacks') })),
          known: known ? { health: integer(known, 'health'), healthMax: count(known, 'healthMax'), guard: integer(known, 'guard'),
            resistance: integer(known, 'resistance'), armor: integer(known, 'armor'), round: count(known, 'round'), stateRevision: count(known, 'stateRevision') } : null,
        };
      }),
      player: { health: integer(player, 'health'), healthMax: count(player, 'healthMax'), focus: integer(player, 'focus'), focusMax: count(player, 'focusMax'),
        guard: integer(player, 'guard'), resistance: integer(player, 'resistance'), armor: integer(player, 'armor'), guarding: flag(player, 'guarding'),
        reactionsLeft: count(player, 'reactionsLeft') },
      objectives: rowsOf(fight, 'objectives').map((row) => ({
        objectiveId: token(row, 'objectiveId', 200), kind: choice(row, 'kind', COMBAT_OBJECTIVE_KINDS), zone: optionalToken(row, 'zone', 200),
        participantId: optionalToken(row, 'participantId', 200), rounds: optionalCount(row, 'rounds'), status: choice(row, 'status', COMBAT_OBJECTIVE_STATUSES) })),
      options,
      outcome: fight['outcome'] === null || fight['outcome'] === undefined ? null : parseCombatVerdict(fight['outcome']),
    },
  };
}
