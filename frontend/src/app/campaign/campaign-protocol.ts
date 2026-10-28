/**
 * Validators and value types for the browser side of the campaign operation protocol
 * (docs/13 sections 3–4, docs/29 section 6): operation bodies, public receipts, stream
 * frames, stream cursors and the campaign-state fields the interface renders.
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
  'narrative.pending',
] as const;
export type StreamType = (typeof STREAM_TYPES)[number];

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

/** One validated stream message. `reason` appears on resets and pending narration only. */
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
