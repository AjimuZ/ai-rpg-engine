/**
 * Read-only browser client for the owner's persisted adventure library (docs/13, docs/25 section 3).
 *
 * The client only issues same-origin GET requests: it bootstraps the local session cookie, lists
 * saved adventures, reads one adventure and lists the playthroughs pinned to it. Every response is
 * validated field by field before it reaches a screen, so a malformed, oversized or foreign payload
 * becomes a typed {@link SavedLibraryError} instead of partially rendered state. The client never
 * writes a server-side "active adventure"; selection stays in the browser route.
 */
import { inject, Injectable, InjectionToken } from '@angular/core';

/** Fetch implementation used by the client; tests replace it to simulate the local server. */
export const LIBRARY_FETCH = new InjectionToken<typeof fetch>('LIBRARY_FETCH', {
  providedIn: 'root',
  // Bind to the global object so the browser does not reject a detached `fetch` call.
  factory: () => globalThis.fetch.bind(globalThis),
});

/** Persisted adventure metadata exactly as the library API publishes it. */
export interface SavedAdventure {
  readonly adventureId: string;
  readonly title: string;
  readonly genre: string;
  readonly sourceLocale: 'en' | 'it';
  readonly metadataRevision: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** One bounded page of saved adventures; `nextAfter` is the opaque cursor for the next page. */
export interface SavedAdventurePage {
  readonly adventures: readonly SavedAdventure[];
  readonly nextAfter: string | null;
}

/** Playthrough metadata pinned to one adventure and one immutable content revision. */
export interface SavedPlaythrough {
  readonly campaignId: string;
  readonly adventureId: string;
  readonly contentRevisionId: string;
  readonly title: string;
  readonly mode: 'EXPLORATION' | 'ENCOUNTER';
  readonly stateRevision: number;
  readonly gameTurn: number;
  readonly updatedAt: string;
  readonly pendingDecisionAvailable: boolean;
}

/**
 * Failure categories a screen can explain without exposing server detail.
 * `aborted` means the caller cancelled the request (for example after switching adventure).
 */
export type SavedLibraryFailure = 'aborted' | 'denied' | 'invalid' | 'missing' | 'unavailable' | 'unconfigured';

/** Typed library failure; `kind` drives the localized message and never carries payload content. */
export class SavedLibraryError extends Error {
  /** Create a failure of one category; the message is diagnostic only and is not rendered. */
  constructor(readonly kind: SavedLibraryFailure, message: string) {
    super(message);
  }
}

// Lowercase canonical UUIDs only: the server emits this form, so anything else is foreign data.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
// ISO-8601 instants as serialized by the backend; the exact precision may vary.
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
// Page size requested from the server; the documented maximum keeps one page bounded.
const PAGE_LIMIT = 50;

/** Return true for a lowercase canonical UUID; any other value fails closed. */
export function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value);
}

/** Throw an `invalid` failure; used by the validators so callers never see partial objects. */
function invalid(field: string): never {
  throw new SavedLibraryError('invalid', `Invalid library field: ${field}`);
}

/** Read a plain JSON object; arrays, null and primitives are rejected as invalid payloads. */
function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(field);
  return value as Record<string, unknown>;
}

/** Read a UUID field; malformed identities would let one adventure's view address another. */
function uuid(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  return isUuid(value) ? value : invalid(field);
}

/** Read a trimmed, non-blank string whose length matches the database constraint. */
function text(source: Record<string, unknown>, field: string, max: number): string {
  const value = source[field];
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) invalid(field);
  return value as string;
}

/** Read a non-negative safe integer; larger values could not round-trip through JSON exactly. */
function count(source: Record<string, unknown>, field: string): number {
  const value = source[field];
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) invalid(field);
  return value as number;
}

/** Read a server instant; display code relies on it parsing as a real date. */
function instant(source: Record<string, unknown>, field: string): string {
  const value = source[field];
  if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) invalid(field);
  return value as string;
}

/** Validate one adventure summary; throws `invalid` for any missing, malformed or unbounded field. */
export function parseAdventure(value: unknown): SavedAdventure {
  const source = record(value, 'adventure');
  const sourceLocale = source['sourceLocale'];
  if (sourceLocale !== 'en' && sourceLocale !== 'it') invalid('sourceLocale');
  return {
    adventureId: uuid(source, 'adventureId'),
    title: text(source, 'title', 200),
    genre: text(source, 'genre', 80),
    sourceLocale,
    metadataRevision: count(source, 'metadataRevision'),
    createdAt: instant(source, 'createdAt'),
    updatedAt: instant(source, 'updatedAt'),
  };
}

/** Validate an adventure page; duplicate identities are rejected because cards are keyed by ID. */
export function parseAdventurePage(value: unknown): SavedAdventurePage {
  const source = record(value, 'listing');
  const list = source['adventures'];
  if (!Array.isArray(list) || list.length > 100) invalid('adventures');
  const adventures = (list as unknown[]).map(parseAdventure);
  if (new Set(adventures.map(/** Identity, never title, distinguishes adventures. */ (item) => item.adventureId)).size !== adventures.length) invalid('adventures');
  const nextAfter = source['nextAfter'];
  if (nextAfter !== null && !isUuid(nextAfter)) invalid('nextAfter');
  return { adventures, nextAfter };
}

/**
 * Validate a playthrough listing for one requested adventure.
 *
 * @param value decoded JSON body
 * @param adventureId the adventure the caller asked about; a row for any other adventure is foreign
 * @returns validated playthroughs in server order
 * @throws SavedLibraryError `invalid` for malformed, duplicate or foreign rows
 */
export function parsePlaythroughs(value: unknown, adventureId: string): SavedPlaythrough[] {
  const source = record(value, 'listing');
  const list = source['campaigns'];
  if (!Array.isArray(list) || list.length > 100) invalid('campaigns');
  const playthroughs = (list as unknown[]).map(/** Validate each row independently. */ (item) => {
    const row = record(item, 'campaign');
    const mode = row['mode'];
    if (mode !== 'EXPLORATION' && mode !== 'ENCOUNTER') invalid('mode');
    if (typeof row['pendingDecisionAvailable'] !== 'boolean') invalid('pendingDecisionAvailable');
    const playthrough: SavedPlaythrough = {
      campaignId: uuid(row, 'campaignId'),
      adventureId: uuid(row, 'adventureId'),
      contentRevisionId: uuid(row, 'contentRevisionId'),
      title: text(row, 'title', 200),
      mode,
      stateRevision: count(row, 'stateRevision'),
      gameTurn: count(row, 'gameTurn'),
      updatedAt: instant(row, 'updatedAt'),
      pendingDecisionAvailable: row['pendingDecisionAvailable'] as boolean,
    };
    // A row from another adventure must never appear inside this adventure's header or list.
    if (playthrough.adventureId !== adventureId) invalid('adventureId');
    return playthrough;
  });
  if (new Set(playthroughs.map(/** Identity, never title, distinguishes playthroughs. */ (item) => item.campaignId)).size !== playthroughs.length) invalid('campaigns');
  if (source['nextAfter'] !== null && !isUuid(source['nextAfter'])) invalid('nextAfter');
  return playthroughs;
}

/** Read-only persisted library access shared by the library and saved-adventure screens. */
@Injectable({ providedIn: 'root' })
export class SavedLibraryClient {
  private readonly fetcher = inject(LIBRARY_FETCH);
  // One in-flight bootstrap is shared so concurrent screens do not race to replace the cookie.
  private session: Promise<void> | undefined;

  /** List one page of saved adventures; `after` continues from a previous page's cursor. */
  async adventures(after: string | null, signal: AbortSignal): Promise<SavedAdventurePage> {
    if (after !== null && !isUuid(after)) throw new SavedLibraryError('invalid', 'Invalid cursor');
    const query = new URLSearchParams({ limit: String(PAGE_LIMIT) });
    if (after) query.set('after', after);
    return parseAdventurePage(await this.read(`/api/v1/adventures?${query}`, signal));
  }

  /** Read one adventure by identity; the returned summary must carry exactly that identity. */
  async adventure(adventureId: string, signal: AbortSignal): Promise<SavedAdventure> {
    if (!isUuid(adventureId)) throw new SavedLibraryError('missing', 'Unknown adventure identity');
    const adventure = parseAdventure(await this.read(`/api/v1/adventures/${adventureId}`, signal));
    if (adventure.adventureId !== adventureId) throw new SavedLibraryError('invalid', 'Foreign adventure');
    return adventure;
  }

  /** List the first page of playthroughs for one adventure; foreign rows fail validation. */
  async playthroughs(adventureId: string, signal: AbortSignal): Promise<SavedPlaythrough[]> {
    if (!isUuid(adventureId)) throw new SavedLibraryError('missing', 'Unknown adventure identity');
    const query = new URLSearchParams({ adventureId, limit: String(PAGE_LIMIT) });
    return parsePlaythroughs(await this.read(`/api/v1/campaigns?${query}`, signal), adventureId);
  }

  /**
   * Perform one admitted GET and decode its JSON body.
   *
   * A 403 may mean the session cookie expired or the server restarted, so the session is
   * bootstrapped again exactly once; a second denial is reported instead of looping.
   */
  private async read(path: string, signal: AbortSignal): Promise<unknown> {
    await this.ensureSession(false);
    let response = await this.get(path, signal);
    if (response.status === 403) {
      await this.ensureSession(true);
      response = await this.get(path, signal);
    }
    return this.decode(response);
  }

  /**
   * Bootstrap the HttpOnly session cookie once, or again when `renew` follows a denial.
   * The shared bootstrap is not tied to any caller's abort signal: one screen switching away
   * must not cancel the session another screen is still waiting for.
   */
  ensureSession(renew: boolean): Promise<void> {
    if (renew || !this.session) {
      const attempt = this.get('/api/v1/session', new AbortController().signal).then(/** Only a 200 JSON reply proves the session. */ async (response) => {
        await this.decode(response);
      });
      // A failed bootstrap is forgotten so the next screen can retry instead of inheriting it.
      attempt.catch(/** Reset on failure; the rejection still reaches this caller. */ () => {
        if (this.session === attempt) this.session = undefined;
      });
      this.session = attempt;
    }
    return this.session;
  }

  /** Issue a same-origin GET; network failures and aborts become typed library failures. */
  private async get(path: string, signal: AbortSignal): Promise<Response> {
    try {
      return await this.fetcher(path, {
        method: 'GET', credentials: 'same-origin', cache: 'no-store', signal,
        headers: { Accept: 'application/json' },
      });
    } catch {
      throw new SavedLibraryError(signal.aborted ? 'aborted' : 'unavailable', 'Library request failed');
    }
  }

  /** Map status codes to failure kinds and parse JSON; non-JSON bodies are invalid. */
  private async decode(response: Response): Promise<unknown> {
    if (response.status === 404) throw new SavedLibraryError('missing', 'Adventure scope unavailable');
    if (response.status === 403) throw new SavedLibraryError('denied', 'Local admission denied');
    if (response.status === 503) {
      // The owner-unconfigured case has a distinct, actionable explanation.
      const body = await response.json().catch(/** Unreadable error bodies are generic outages. */ () => undefined) as { code?: unknown } | undefined;
      throw new SavedLibraryError(body?.code === 'LOCAL_OWNER_UNCONFIGURED' ? 'unconfigured' : 'unavailable', 'Library unavailable');
    }
    if (response.status !== 200) throw new SavedLibraryError('unavailable', `Library status ${response.status}`);
    if (!(response.headers.get('Content-Type') ?? '').toLowerCase().startsWith('application/json')) {
      throw new SavedLibraryError('invalid', 'Library reply is not JSON');
    }
    try {
      return await response.json();
    } catch {
      throw new SavedLibraryError('invalid', 'Library reply is not valid JSON');
    }
  }
}
