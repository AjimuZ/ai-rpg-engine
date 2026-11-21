/**
 * Strict reader of a pack's music declaration as the engine delivers it (P17.2-D,
 * `GET /api/v1/campaigns/{campaignId}/music?adventureId=`): the pack's mood policy and its own credited tracks.
 *
 * The body is untrusted until every field passes. A track never carries a URL: its address is built here from the
 * route scope and the validated SHA-256, on the engine's own asset route, so a pack cannot point the player at another
 * origin. The policy is read by {@link parseMoodPolicy}, which accepts only the closed public states.
 */
import { CampaignProtocolError } from '../campaign/campaign-protocol';
import { LICENSES, Mood, MOODS, MusicRecording } from './music-catalog';
import { MoodPolicy, parseMoodPolicy } from './music-mood';

/** What a playthrough plays from its pack: the policy (null keeps the default) and the pack's tracks. */
export interface PackMusic {
  readonly policy: MoodPolicy | null;
  readonly tracks: readonly MusicRecording[];
}

/** A pack without a music declaration, and the fallback whenever the listing cannot be read. */
export const NO_PACK_MUSIC: PackMusic = { policy: null, tracks: [] };

/** Licenses a pack track may carry: the reviewed music licenses plus MIT (docs/adr/0054); equal to `TRACK_LICENSES`. */
export const TRACK_LICENSES: Readonly<Record<string, { readonly name: string; readonly url: string }>> = {
  'CC0-1.0': { name: 'Creative Commons Zero 1.0 Universal', url: LICENSES['CC0-1.0']! },
  'CC-BY-3.0': { name: 'Creative Commons Attribution 3.0 Unported', url: LICENSES['CC-BY-3.0']! },
  'CC-BY-4.0': { name: 'Creative Commons Attribution 4.0 International', url: LICENSES['CC-BY-4.0']! },
  MIT: { name: 'MIT License', url: 'https://opensource.org/license/mit' },
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const REFERENCE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
/** Same rule as the backend credit pattern (Java `\p{Cntrl}`): 1 to 200 characters, no ASCII control, not only spaces. */
// eslint-disable-next-line no-control-regex
const CREDIT = /^(?=.*\S)[^\x00-\x1F\x7F]{1,200}$/;
const AUDIO_TYPES = ['audio/ogg', 'audio/mpeg', 'audio/wav'];
const TRACK_KEYS = 'creator,id,license,licenseUrl,mediaType,modifications,mood,notices,sha256,source,title';

function invalid(what: string): never { throw new CampaignProtocolError('invalid', `Invalid music listing: ${what}`); }

function exactKeys(value: unknown, keys: string, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(what);
  const record = value as Record<string, unknown>;
  if (Object.keys(record).sort().join(',') !== keys) invalid(what);
  return record;
}

/**
 * Validate the music listing of one campaign.
 *
 * @param value decoded JSON body
 * @param adventureId adventure requested by the route
 * @param campaignId campaign requested by the route
 * @returns the pack's policy (null when it declares none) and its tracks as playable records addressed by hash
 * @throws CampaignProtocolError `invalid` when the scope differs, a key is missing or extra, the policy names a state
 *   or mood outside the closed lists, a track lacks credit text, names a license outside {@link TRACK_LICENSES} or a
 *   URL other than that license's, a hash that is not SHA-256 hex, a non-audio media type, or repeats an ID or mood.
 *   Nothing is repaired; the caller keeps {@link NO_PACK_MUSIC}.
 */
export function parseMusicListing(value: unknown, adventureId: string, campaignId: string): PackMusic {
  if (!UUID.test(adventureId) || !UUID.test(campaignId)) invalid('scope');
  const body = exactKeys(value, 'policy,scope,tracks', 'body');
  const scope = exactKeys(body['scope'], 'adventureId,campaignId', 'scope');
  if (scope['adventureId'] !== adventureId || scope['campaignId'] !== campaignId) invalid('scope');
  let policy: MoodPolicy | null = null;
  if (body['policy'] !== null) {
    policy = parseMoodPolicy(body['policy']);
    if (policy === null || Object.keys(body['policy'] as object).length === 0) invalid('policy');
  }
  const list = body['tracks'];
  if (!Array.isArray(list) || list.length > MOODS.length) invalid('tracks');
  const ids = new Set<string>();
  const moods = new Set<string>();
  const tracks = list.map((raw): MusicRecording => {
    const track = exactKeys(raw, TRACK_KEYS, 'track');
    const text = (key: string): string => {
      const field = track[key];
      if (typeof field !== 'string' || !CREDIT.test(field)) invalid(key);
      return field;
    };
    const id = track['id'];
    if (typeof id !== 'string' || !REFERENCE.test(id) || ids.has(id)) invalid('id');
    const mood = track['mood'];
    if (typeof mood !== 'string' || !(MOODS as readonly string[]).includes(mood) || moods.has(mood)) invalid('mood');
    const license = typeof track['license'] === 'string' ? TRACK_LICENSES[track['license']] : undefined;
    if (license === undefined || track['licenseUrl'] !== license.url) invalid('license');
    const hash = track['sha256'];
    if (typeof hash !== 'string' || !/^[0-9a-f]{64}$/.test(hash)) invalid('sha256');
    if (typeof track['mediaType'] !== 'string' || !AUDIO_TYPES.includes(track['mediaType'])) invalid('mediaType');
    ids.add(id);
    moods.add(mood);
    return {
      // Prefixed so a pack track can never be taken for a catalog record of the same ID.
      id: `pack:${id}`, title: text('title'), creator: text('creator'), mood: mood as Mood,
      sourcePage: '', sourceText: text('source'), mediaFile: hash,
      licenseId: track['license'] as string, licenseName: license.name, licenseUrl: license.url,
      notices: text('notices'), modifications: text('modifications'), verifiedOn: '',
      packAsset: { adventureId, campaignId, sha256: hash },
    };
  });
  return { policy, tracks };
}
