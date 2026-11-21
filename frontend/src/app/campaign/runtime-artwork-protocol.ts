/**
 * Wire contract of the runtime artwork routes (P17.6-C, docs/13): strict parsing of the listing of
 * `GET /api/v1/campaigns/{id}/artwork/requests` and of the answer to a `POST` on the same path. A reply with another scope, an
 * unknown value, or a missing or extra field is refused whole, so nothing the server did not promise reaches the screen.
 */
import { CampaignProtocolError } from './campaign-protocol';

/** Categories a runtime job can make. */
export const IMAGE_CATEGORIES = ['scene', 'portrait', 'item'] as const;
export type ImageCategory = (typeof IMAGE_CATEGORIES)[number];
export const IMAGE_STATES = ['missing', 'pending', 'ready', 'failed'] as const;
export type ImageState = (typeof IMAGE_STATES)[number];
export const IMAGE_CAPABILITIES = ['enabled', 'disabled', 'unavailable'] as const;
export type ImageCapability = (typeof IMAGE_CAPABILITIES)[number];
export const IMAGE_ADMISSIONS = ['queued', 'coalesced', 'reused', 'stale-appearance', 'disabled', 'unavailable', 'queue-full',
  'budget-exhausted'] as const;
export type ImageAdmission = (typeof IMAGE_ADMISSIONS)[number];

/** One subject the player sees now, exactly as the server lists it. */
export interface ImageSubject {
  readonly category: ImageCategory;
  /** Stable public reference: location ID, actor ID or item definition ID, the same the artwork listing uses. */
  readonly subject: string;
  /** Public name in the pack's source language, or the actor's display name. */
  readonly label: string;
  readonly state: ImageState;
  /** Fixed lowercase code when `failed`, else null. */
  readonly errorCode: string | null;
}

/** The listing: capability, the campaign's budget use and the subjects in view. */
export interface ImageRequests {
  readonly capability: ImageCapability;
  readonly budget: { readonly charged: number; readonly limit: number; readonly queued: number; readonly running: boolean };
  readonly subjects: readonly ImageSubject[];
}

/** The answer to one request: the job service's verdict and the subject's state afterwards. */
export interface ImageAnswer { readonly admission: ImageAdmission; readonly subject: ImageSubject }

/** Most subjects a listing may hold: one scene, 32 portraits and 64 items on the server. */
const MAX_SUBJECTS = 97;

function invalid(field: string): never { throw new CampaignProtocolError('invalid', `Invalid artwork request field: ${field}`); }

function object(value: unknown, field: string): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : invalid(field);
}

/** Exactly the named keys, so a field the server adds (or a hidden one) is refused instead of shown. */
function exact(source: Record<string, unknown>, keys: readonly string[], field: string): void {
  if (Object.keys(source).sort().join(',') !== [...keys].sort().join(',')) invalid(field);
}

function choice<T extends string>(value: unknown, allowed: readonly T[], field: string): T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value as T : invalid(field);
}

function count(value: unknown, field: string): number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 1_000_000 ? value : invalid(field);
}

/** Validate one subject; the reference and code forms are those the server promises. */
function subject(value: unknown): ImageSubject {
  const row = object(value, 'subject');
  exact(row, ['category', 'subject', 'label', 'state', 'errorCode'], 'subject');
  const reference = typeof row['subject'] === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(row['subject']) ? row['subject'] : invalid('subject');
  const label = typeof row['label'] === 'string' && row['label'].trim() !== '' && row['label'].length <= 200 ? row['label'] : invalid('label');
  const state = choice(row['state'], IMAGE_STATES, 'state');
  const code = row['errorCode'];
  if (state === 'failed' ? typeof code !== 'string' || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(code) : code !== null) invalid('errorCode');
  return { category: choice(row['category'], IMAGE_CATEGORIES, 'category'), subject: reference, label, state, errorCode: code as string | null };
}

/**
 * Validate a listing against the route's scope.
 *
 * @throws CampaignProtocolError `invalid` for another scope, an unknown value, a missing or extra field, or too many subjects
 */
export function parseImageRequests(value: unknown, adventureId: string, campaignId: string): ImageRequests {
  const source = object(value, 'listing');
  exact(source, ['scope', 'capability', 'budget', 'subjects'], 'listing');
  const scope = object(source['scope'], 'scope');
  exact(scope, ['adventureId', 'campaignId'], 'scope');
  if (scope['adventureId'] !== adventureId || scope['campaignId'] !== campaignId) invalid('scope');
  const budget = object(source['budget'], 'budget');
  exact(budget, ['charged', 'limit', 'queued', 'running'], 'budget');
  if (typeof budget['running'] !== 'boolean') invalid('running');
  const subjects = source['subjects'];
  if (!Array.isArray(subjects) || subjects.length > MAX_SUBJECTS) invalid('subjects');
  return {
    capability: choice(source['capability'], IMAGE_CAPABILITIES, 'capability'),
    budget: { charged: count(budget['charged'], 'charged'), limit: count(budget['limit'], 'limit'), queued: count(budget['queued'], 'queued'),
      running: budget['running'] as boolean },
    subjects: subjects.map(subject),
  };
}

/** Validate the answer to one request. @throws CampaignProtocolError `invalid` for any other shape */
export function parseImageAnswer(value: unknown): ImageAnswer {
  const source = object(value, 'answer');
  exact(source, ['admission', 'subject'], 'answer');
  return { admission: choice(source['admission'], IMAGE_ADMISSIONS, 'admission'), subject: subject(source['subject']) };
}

/** Stable key of a subject within one playthrough. */
export function subjectKey(item: Pick<ImageSubject, 'category' | 'subject'>): string { return `${item.category}:${item.subject}`; }
