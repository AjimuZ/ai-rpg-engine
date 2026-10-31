/**
 * Same-origin browser client for the owner's model slots (P21.3-D, docs/13, docs/34 section 12).
 *
 * It reads `GET /api/v1/models/status` and sends `PUT /api/v1/settings/models` and
 * `POST /api/v1/models/probe` with the local session cookie and the CSRF proof the session
 * bootstrap returns. Responses are validated field by field, so a malformed or foreign payload
 * becomes a typed {@link ModelsError} instead of partly rendered state. The API publishes identifiers
 * only; the client never asks for, stores or sends a key or an endpoint.
 */
import { inject, Injectable } from '@angular/core';
import { LIBRARY_FETCH } from '../library/saved-library-client';

/** The four slots in the order the server lists them. */
export const SLOT_IDS = ['text.light', 'text.standard', 'text.deliberate', 'image'] as const;
export type SlotId = typeof SLOT_IDS[number];

/** Billing and privacy classes of an assignment; the API fixes the codes of each. */
export const CLASS_IDS = ['local', 'subscription', 'metered'] as const;
export type ClassId = typeof CLASS_IDS[number];
export type BillingCode = 'free' | 'plan' | 'per-use';
export type PrivacyCode = 'this-machine' | 'runtime-vendor' | 'api-vendor';

/** Protocol adapters each slot family accepts; membership names the protocol, not an installed adapter. */
export const TEXT_BACKENDS = ['codex', 'claude', 'local', 'openai-compatible', 'anthropic-api'] as const;
export const IMAGE_BACKENDS = ['local-image', 'a1111-http', 'openai-images'] as const;
/** The one backend whose class the owner declares. */
export const DECLARED_CLASS_BACKEND = 'openai-compatible';
/** Fallbacks the server accepts per slot. */
export const MAX_FALLBACKS = 4;

/** Fixed billing and privacy codes per class, as the backend publishes them; used where only a class name is known. */
export const CLASS_BADGES: Readonly<Record<ClassId, ClassBadge>> = {
  local: { class: 'local', billing: 'free', privacy: 'this-machine', leavesMachine: false },
  subscription: { class: 'subscription', billing: 'plan', privacy: 'runtime-vendor', leavesMachine: true },
  metered: { class: 'metered', billing: 'per-use', privacy: 'api-vendor', leavesMachine: true },
};

/** Class badge of an assignment. */
export interface ClassBadge {
  readonly class: ClassId;
  readonly billing: BillingCode;
  readonly privacy: PrivacyCode;
  readonly leavesMachine: boolean;
}

/** One configured same-class alternative. */
export interface Fallback {
  readonly backend: string;
  readonly model: string;
  readonly effort: string;
}

export type ProbeState = 'not-run' | 'ready' | 'failed' | 'unreachable';

/** Last readiness probe: a state, a fixed reason code and timing; never provider text. */
export interface ProbeResult {
  readonly state: ProbeState;
  readonly code: string | null;
  readonly at: string | null;
  readonly elapsedMillis: number;
}

export type SlotSource = 'settings' | 'environment' | 'unassigned';

/** One slot exactly as the status route publishes it. */
export interface SlotStatus {
  readonly slot: SlotId;
  readonly source: SlotSource;
  readonly backend: string | null;
  readonly model: string | null;
  readonly effort: string | null;
  /** Null for an unassigned slot and for an environment endpoint whose class is not known. */
  readonly badge: ClassBadge | null;
  readonly fallbacks: readonly Fallback[];
  readonly acknowledged: { readonly class: ClassId; readonly at: string } | null;
  readonly probe: ProbeResult;
  readonly updatedAt: string | null;
}

/** An assignment to save; `effort` is null only for the image slot. */
export interface SlotRequest {
  readonly slot: SlotId;
  readonly backend: string;
  readonly model: string;
  readonly effort: string | null;
  readonly declaredClass: ClassId | null;
  readonly fallbacks: readonly Fallback[];
  readonly acknowledgeClass: ClassId | null;
}

/** Result of a save: stored, awaiting acknowledgement of a class change, or refused with a fixed code. */
export type SaveOutcome =
  | { readonly kind: 'saved'; readonly slot: SlotStatus }
  | { readonly kind: 'acknowledge'; readonly from: ClassId | null; readonly to: ClassId }
  | { readonly kind: 'refused'; readonly code: string };

/** Result of a probe: the stored result, or a fixed refusal code. */
export type ProbeOutcome =
  | { readonly kind: 'probed'; readonly slot: SlotStatus }
  | { readonly kind: 'refused'; readonly code: string };

/** Failure categories a panel can explain without exposing server detail. */
export type ModelsFailure = 'denied' | 'invalid' | 'unavailable' | 'unconfigured';

/** Typed failure; `kind` selects the localized message and the diagnostic text is never rendered. */
export class ModelsError extends Error {
  /** Create a failure of one category. */
  constructor(readonly kind: ModelsFailure, message: string) {
    super(message);
  }
}

// ISO-8601 instants as serialized by the backend; the exact precision may vary.
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
// Stable public codes are upper snake case; lowercase probe codes are matched separately.
const ERROR_CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
const PROBE_CODE = /^[a-z][a-z0-9-]{0,63}$/;
const BILLING: readonly string[] = ['free', 'plan', 'per-use'];
const PRIVACY: readonly string[] = ['this-machine', 'runtime-vendor', 'api-vendor'];
const PROBE_STATES: readonly string[] = ['not-run', 'ready', 'failed', 'unreachable'];
const SOURCES: readonly string[] = ['settings', 'environment', 'unassigned'];

/** Throw an `invalid` failure; used by the validators so callers never see partial objects. */
function invalid(field: string): never {
  throw new ModelsError('invalid', `Invalid model field: ${field}`);
}

/** Read a plain JSON object; arrays, null and primitives are rejected. */
function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(field);
  return value as Record<string, unknown>;
}

/** Read a bounded, non-blank string identifier. */
function identifier(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 128) invalid(field);
  return value as string;
}

/** Read an identifier or null. */
function optionalIdentifier(value: unknown, field: string): string | null {
  return value === null ? null : identifier(value, field);
}

/** Read a server instant that parses as a real date. */
function instant(value: unknown, field: string): string {
  if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) invalid(field);
  return value as string;
}

/** Read a class identifier from the closed set. */
function classId(value: unknown, field: string): ClassId {
  if (!CLASS_IDS.includes(value as ClassId)) invalid(field);
  return value as ClassId;
}

/** Validate one class badge; codes outside the closed sets or disagreeing with the class are foreign. */
function parseBadge(value: unknown): ClassBadge {
  const source = record(value, 'class');
  const id = classId(source['class'], 'class.class');
  if (!BILLING.includes(source['billing'] as string) || !PRIVACY.includes(source['privacy'] as string)
    || typeof source['leavesMachine'] !== 'boolean') invalid('class');
  const badge: ClassBadge = { class: id, billing: source['billing'] as BillingCode, privacy: source['privacy'] as PrivacyCode,
    leavesMachine: source['leavesMachine'] };
  const fixed = CLASS_BADGES[id];
  if (badge.billing !== fixed.billing || badge.privacy !== fixed.privacy || badge.leavesMachine !== fixed.leavesMachine) invalid('class');
  return badge;
}

/** Validate one slot; throws `invalid` for any missing, malformed or inconsistent field. */
export function parseSlot(value: unknown): SlotStatus {
  const source = record(value, 'slot');
  if (!SLOT_IDS.includes(source['slot'] as SlotId)) invalid('slot');
  if (!SOURCES.includes(source['source'] as string)) invalid('source');
  const fallbacksValue = source['fallbacks'];
  if (!Array.isArray(fallbacksValue) || fallbacksValue.length > MAX_FALLBACKS) invalid('fallbacks');
  const fallbacks = (fallbacksValue as unknown[]).map(/** Validate each fallback independently. */ (entry) => {
    const item = record(entry, 'fallback');
    return { backend: identifier(item['backend'], 'fallback.backend'), model: identifier(item['model'], 'fallback.model'),
      effort: identifier(item['effort'], 'fallback.effort') };
  });
  const acknowledged = source['acknowledged'] === null ? null : (() => {
    const item = record(source['acknowledged'], 'acknowledged');
    return { class: classId(item['class'], 'acknowledged.class'), at: instant(item['at'], 'acknowledged.at') };
  })();
  const probe = record(source['probe'], 'probe');
  const state = probe['state'];
  if (!PROBE_STATES.includes(state as string)) invalid('probe.state');
  const code = probe['code'];
  if (code !== null && (typeof code !== 'string' || !PROBE_CODE.test(code))) invalid('probe.code');
  const at = probe['at'] === null ? null : instant(probe['at'], 'probe.at');
  const elapsed = probe['elapsedMillis'];
  if (typeof elapsed !== 'number' || !Number.isSafeInteger(elapsed) || elapsed < 0) invalid('probe.elapsedMillis');
  // The backend stores a reason exactly for a failed or unreachable result, and a time exactly for a run one.
  if ((state === 'failed' || state === 'unreachable') !== (code !== null) || (state !== 'not-run') !== (at !== null)) invalid('probe');
  const slot: SlotStatus = {
    slot: source['slot'] as SlotId, source: source['source'] as SlotSource,
    backend: optionalIdentifier(source['backend'], 'backend'), model: optionalIdentifier(source['model'], 'model'),
    effort: optionalIdentifier(source['effort'], 'effort'),
    badge: source['class'] === null ? null : parseBadge(source['class']),
    fallbacks, acknowledged,
    probe: { state: state as ProbeState, code: code as string | null, at, elapsedMillis: elapsed as number },
    updatedAt: source['updatedAt'] === null ? null : instant(source['updatedAt'], 'updatedAt'),
  };
  // An unassigned slot has no assignment fields at all; an assigned one always has backend and model.
  if ((slot.source === 'unassigned') !== (slot.backend === null) || (slot.backend === null) !== (slot.model === null)) invalid('assignment');
  return slot;
}

/** Validate a status body: exactly the four known slots, once each, returned in canonical order. */
export function parseStatus(value: unknown): SlotStatus[] {
  const source = record(value, 'status');
  const list = source['slots'];
  if (!Array.isArray(list) || list.length !== SLOT_IDS.length) invalid('slots');
  const slots = (list as unknown[]).map(parseSlot);
  if (SLOT_IDS.some(/** Every known slot must be present exactly once. */ (id) => slots.filter((slot) => slot.slot === id).length !== 1)) invalid('slots');
  return SLOT_IDS.map(/** Canonical order, whatever order the server used. */ (id) => slots.find((slot) => slot.slot === id)!);
}

/** Owner access to the model slots, shared by every screen that shows them. */
@Injectable({ providedIn: 'root' })
export class ModelsClient {
  private readonly fetcher = inject(LIBRARY_FETCH);
  // One in-flight bootstrap is shared so concurrent calls do not race to replace the cookie.
  private session: Promise<string> | undefined;

  /**
   * Read the four slots.
   *
   * @throws ModelsError `denied` after one session renewal, `unconfigured` when the installation has
   *         no owner, `unavailable` for outages and `invalid` for a payload that fails validation
   */
  async status(signal: AbortSignal): Promise<SlotStatus[]> {
    const reply = await this.send('GET', '/api/v1/models/status', null, signal);
    if (reply.status !== 200) throw this.failure(reply);
    return parseStatus(reply.body);
  }

  /**
   * Save one slot. A class change that was not acknowledged comes back as `acknowledge` and stores
   * nothing; nothing is stored on any other non-`saved` outcome either.
   */
  async save(request: SlotRequest, signal: AbortSignal): Promise<SaveOutcome> {
    const reply = await this.send('PUT', '/api/v1/settings/models', requestBody(request), signal);
    if (reply.status === 200) return { kind: 'saved', slot: parseSlot(record(reply.body, 'reply')['slot']) };
    if (reply.status === 409) {
      const body = record(reply.body, 'conflict');
      if (body['code'] !== 'CLASS_ACKNOWLEDGEMENT_REQUIRED') throw this.failure(reply);
      return { kind: 'acknowledge', from: body['from'] === null ? null : classId(body['from'], 'from'), to: classId(body['to'], 'to') };
    }
    if ([400, 413, 422].includes(reply.status)) return { kind: 'refused', code: refusalCode(reply.body) };
    throw this.failure(reply);
  }

  /** Probe the stored assignment of one slot and return the slot with its new result. */
  async probe(slot: SlotId, signal: AbortSignal): Promise<ProbeOutcome> {
    const reply = await this.send('POST', '/api/v1/models/probe', JSON.stringify({ slot }), signal);
    if (reply.status === 200) return { kind: 'probed', slot: parseSlot(record(reply.body, 'reply')['slot']) };
    if ([400, 404, 413].includes(reply.status)) return { kind: 'refused', code: refusalCode(reply.body) };
    throw this.failure(reply);
  }

  /** Map a non-success reply to the failure category a screen can explain. */
  private failure(reply: Reply): ModelsError {
    if (reply.status === 403) return new ModelsError('denied', 'Local admission denied');
    if (reply.status === 503 && reply.body !== null && typeof reply.body === 'object'
      && (reply.body as { code?: unknown }).code === 'LOCAL_OWNER_UNCONFIGURED') return new ModelsError('unconfigured', 'Owner not configured');
    return new ModelsError('unavailable', `Model settings status ${reply.status}`);
  }

  /**
   * Perform one admitted request. A 403 may mean the session cookie expired or the server restarted,
   * so the session is bootstrapped again exactly once; a second denial is reported instead of looping.
   * A denied request was refused before it was processed, so repeating a write is safe.
   */
  private async send(method: string, path: string, body: string | null, signal: AbortSignal): Promise<Reply> {
    let reply = await this.exchange(method, path, body, await this.csrf(false, signal), signal);
    if (reply.status === 403) reply = await this.exchange(method, path, body, await this.csrf(true, signal), signal);
    return reply;
  }

  /** Bootstrap the HttpOnly session once, or again after a denial, and return its CSRF proof. */
  private csrf(renew: boolean, signal: AbortSignal): Promise<string> {
    if (renew || !this.session) {
      const attempt = this.exchange('GET', '/api/v1/session', null, null, signal).then(/** Only a 200 reply with a token proves the session. */ (reply) => {
        const token = reply.status === 200 && reply.body !== null && typeof reply.body === 'object' ? (reply.body as { csrfToken?: unknown }).csrfToken : undefined;
        if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(token)) throw this.failure(reply);
        return token;
      });
      // A failed bootstrap is forgotten so the next call can retry instead of inheriting it.
      attempt.catch(/** Reset on failure; the rejection still reaches this caller. */ () => {
        if (this.session === attempt) this.session = undefined;
      });
      this.session = attempt;
    }
    return this.session;
  }

  /** Issue one same-origin request and decode a JSON body; network failures become `unavailable`. */
  private async exchange(method: string, path: string, body: string | null, csrf: string | null, signal: AbortSignal): Promise<Reply> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== null) headers['Content-Type'] = 'application/json';
    if (csrf !== null && method !== 'GET') headers['X-CSRF-Token'] = csrf;
    let response: Response;
    try {
      response = await this.fetcher(path, { method, credentials: 'same-origin', cache: 'no-store', signal, headers, ...(body === null ? {} : { body }) });
    } catch {
      throw new ModelsError('unavailable', 'Model settings request failed');
    }
    let decoded: unknown = null;
    if ((response.headers.get('Content-Type') ?? '').toLowerCase().startsWith('application/json')) {
      try {
        decoded = await response.json();
      } catch {
        if (response.status === 200) throw new ModelsError('invalid', 'Model settings reply is not valid JSON');
      }
    } else if (response.status === 200) {
      throw new ModelsError('invalid', 'Model settings reply is not JSON');
    }
    return { status: response.status, body: decoded };
  }
}

/** A decoded reply; `body` is null when the reply carried no JSON. */
interface Reply {
  readonly status: number;
  readonly body: unknown;
}

/** Serialize a request as the closed object the API accepts; unset optional fields are omitted. */
export function requestBody(request: SlotRequest): string {
  const image = request.slot === 'image';
  const body: Record<string, unknown> = { slot: request.slot, backend: request.backend, model: request.model };
  if (!image) body['effort'] = request.effort;
  if (request.declaredClass !== null) body['declaredClass'] = request.declaredClass;
  if (request.fallbacks.length > 0) {
    body['fallbacks'] = request.fallbacks.map(/** Image entries carry no effort. */ (entry) => image
      ? { backend: entry.backend, model: entry.model } : { backend: entry.backend, model: entry.model, effort: entry.effort });
  }
  if (request.acknowledgeClass !== null) body['acknowledgeClass'] = request.acknowledgeClass;
  return JSON.stringify(body);
}

/** Read the fixed public code of an error body; anything else is the empty code, which maps to a generic message. */
function refusalCode(body: unknown): string {
  const code = body !== null && typeof body === 'object' ? (body as { code?: unknown }).code : undefined;
  return typeof code === 'string' && ERROR_CODE.test(code) ? code : '';
}
