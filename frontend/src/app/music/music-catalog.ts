/**
 * The reviewed music catalog as the client sees it (P17.2-B): the same two records as
 * `backend/src/main/resources/music/catalog.json` (rights checked on 2026-10-23) and the one function that turns a
 * record into a media address.
 *
 * The runtime selects entries from this finite list; it never accepts a URL from a story, a pack or a stream
 * event. The address is derived only from a reviewed file name on the single approved host, so an unreviewed
 * origin cannot be reached. Nothing here fetches anything. Keep the list equal to the backend catalog by hand
 * (no automated parity check exists yet) and recheck the rights before adding a record.
 */

/** Moods a public presentation state may map to; equal to `MusicCatalog.MOODS` in the backend. */
export const MOODS = ['exploration', 'dialogue', 'rest', 'tension', 'combat', 'victory', 'defeat'] as const;
export type Mood = (typeof MOODS)[number];

/** One reviewed recording with its complete rights record. */
export interface MusicRecording {
  readonly id: string;
  readonly title: string;
  readonly creator: string;
  readonly mood: Mood;
  readonly sourcePage: string;
  readonly mediaFile: string;
  readonly licenseId: string;
  readonly licenseName: string;
  readonly licenseUrl: string;
  readonly notices: string;
  readonly modifications: string;
  readonly verifiedOn: string;
  /** File name of a permitted local copy under {@link LOCAL_BASE}; absent for an online recording. */
  readonly localFile?: string;
  /** SHA-256 (lowercase hex) of that local copy; present exactly when {@link localFile} is. */
  readonly localHash?: string;
  /**
   * For a track shipped in the playthrough's pack (P17.2-D): the scope and hash of its sealed file, served by the engine's
   * own asset route. Such a record has an empty {@link sourcePage} and {@link verifiedOn}, and states its origin in
   * {@link sourceText}.
   */
  readonly packAsset?: { readonly adventureId: string; readonly campaignId: string; readonly sha256: string };
  /** Plain-text origin of a pack track (never a link). */
  readonly sourceText?: string;
}

/** Reviewed recordings in catalog order. */
export const MUSIC_CATALOG: readonly MusicRecording[] = [
  {
    id: 'wholesome', title: 'Wholesome', creator: 'Kevin MacLeod (incompetech.com)', mood: 'exploration',
    sourcePage: 'https://commons.wikimedia.org/wiki/File:Wholesome_by_Kevin_MacLeod.ogg',
    mediaFile: 'Wholesome_by_Kevin_MacLeod.ogg',
    licenseId: 'CC-BY-4.0', licenseName: 'Creative Commons Attribution 4.0 International',
    licenseUrl: 'https://creativecommons.org/licenses/by/4.0/',
    notices: 'Attribution required: title, creator, source and license link. No other notice was supplied on the file page.',
    modifications: 'None: no audio is copied, trimmed or re-encoded.', verifiedOn: '2026-10-23',
  },
  {
    id: 'darkest-child', title: 'Darkest Child (ISRC USUAN1100783)', creator: 'Kevin MacLeod (incompetech.com)', mood: 'tension',
    sourcePage: 'https://commons.wikimedia.org/wiki/File:Darkest_Child_%28ISRC_USUAN1100783%29.mp3',
    mediaFile: 'Darkest_Child_(ISRC_USUAN1100783).mp3',
    licenseId: 'CC-BY-3.0', licenseName: 'Creative Commons Attribution 3.0 Unported',
    licenseUrl: 'https://creativecommons.org/licenses/by/3.0/',
    notices: 'Attribution required: title, creator, source and license link. No other notice was supplied on the file page.',
    modifications: 'None: no audio is copied, trimmed or re-encoded.', verifiedOn: '2026-10-23',
  },
];

/** The single approved media origin; the same host as the reviewed source pages. */
export const MEDIA_ORIGIN = 'https://commons.wikimedia.org';

/**
 * The reviewed recording for a mood.
 *
 * @returns the first catalog record of that mood, or undefined: silence is the valid answer for a mood
 *   without an admitted recording, and no other mood's track stands in for it
 */
export function recordingFor(mood: Mood, catalog: readonly MusicRecording[] = MUSIC_CATALOG): MusicRecording | undefined {
  return catalog.find(/** One record per mood is enough while the catalog is this small. */ (record) => record.mood === mood);
}

/**
 * The media address of a reviewed recording. An online record uses Commons' documented file redirect (which may
 * redirect to the file host), built from the reviewed file name only; a local record uses its own file under
 * {@link LOCAL_BASE}. Real-host playback has not been verified.
 */
export function mediaUrl(record: MusicRecording): string {
  const pack = record.packAsset;
  if (pack !== undefined) return `/api/v1/campaigns/${pack.campaignId}/assets/${pack.sha256}?adventureId=${pack.adventureId}`;
  if (record.localFile !== undefined) return `${LOCAL_BASE}${encodeURIComponent(record.localFile)}`;
  return `${MEDIA_ORIGIN}/wiki/Special:FilePath/${encodeURIComponent(record.mediaFile)}`;
}

/** Directory of the installation's own music files; a local record names a file here and nothing else. */
export const LOCAL_BASE = '/assets/music/';

/** Admitted licenses: ID to the exact license URL of that version; equal to `MusicCatalog.LICENSES` in the backend. */
export const LICENSES: Readonly<Record<string, string>> = {
  'CC0-1.0': 'https://creativecommons.org/publicdomain/zero/1.0/',
  'CC-BY-3.0': 'https://creativecommons.org/licenses/by/3.0/',
  'CC-BY-4.0': 'https://creativecommons.org/licenses/by/4.0/',
};
/** Approved source hosts; equal to `MusicCatalog.SOURCE_HOSTS` in the backend. */
const SOURCE_HOSTS: readonly string[] = ['commons.wikimedia.org'];
const LOCAL_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.(ogg|mp3|wav|m4a)$/;
const TEXT_FIELDS = ['id', 'title', 'creator', 'sourcePage', 'mediaFile', 'licenseId', 'licenseName', 'licenseUrl', 'notices', 'modifications', 'verifiedOn'] as const;

/**
 * Admit one locally installed track under the same rights review as an online one (P17.2-C).
 *
 * @param candidate an untrusted record from a local catalog
 * @returns the record, or null when it lacks any credit field, names an unknown mood, a license outside the
 *   admitted list or whose URL is not that version's, a source page off the approved HTTPS hosts, a verification
 *   date that is not ISO, a file name that is not a plain audio file name, or a hash that is not SHA-256 hex.
 *   A refused record is dropped, never repaired, so an unreviewed file cannot sound.
 */
export function admitLocal(candidate: unknown): MusicRecording | null {
  if (typeof candidate !== 'object' || candidate === null) return null;
  const value = candidate as Record<string, unknown>;
  for (const field of TEXT_FIELDS) {
    if (typeof value[field] !== 'string' || (value[field] as string).trim() === '') return null;
  }
  const text = (field: string) => value[field] as string;
  if (!(MOODS as readonly string[]).includes(String(value['mood']))) return null;
  if (LICENSES[text('licenseId')] !== text('licenseUrl')) return null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text('verifiedOn'))) return null;
  const file = value['localFile'];
  const hash = value['localHash'];
  if (typeof file !== 'string' || !LOCAL_FILE.test(file) || typeof hash !== 'string' || !/^[0-9a-f]{64}$/.test(hash)) return null;
  try {
    const source = new URL(text('sourcePage'));
    if (source.protocol !== 'https:' || !SOURCE_HOSTS.includes(source.hostname)) return null;
  } catch { return null; }
  return {
    id: text('id'), title: text('title'), creator: text('creator'), mood: value['mood'] as Mood, sourcePage: text('sourcePage'),
    mediaFile: text('mediaFile'), licenseId: text('licenseId'), licenseName: text('licenseName'), licenseUrl: text('licenseUrl'),
    notices: text('notices'), modifications: text('modifications'), verifiedOn: text('verifiedOn'), localFile: file, localHash: hash,
  };
}

/**
 * The credit text of one recording: title, creator, source, license with its link, notices, disclosed
 * modifications and the date the rights were checked. Used by the current-track view and every credits export, so
 * they cannot drift apart.
 */
export function creditText(record: MusicRecording): string {
  return [
    `"${record.title}" — ${record.creator}`,
    `Source: ${record.packAsset ? record.sourceText : record.sourcePage}`,
    `License: ${record.licenseName} (${record.licenseId}), ${record.licenseUrl}`,
    `Notices: ${record.notices}`,
    `Modifications: ${record.modifications}`,
    record.packAsset
      ? `Rights: as declared by the adventure pack; file SHA-256 ${record.packAsset.sha256}`
      : `Rights checked: ${record.verifiedOn}${record.localHash ? `; local copy SHA-256 ${record.localHash}` : ''}`,
  ].join('\n');
}
