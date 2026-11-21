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
export function recordingFor(mood: Mood): MusicRecording | undefined {
  return MUSIC_CATALOG.find(/** One record per mood is enough while the catalog is this small. */ (record) => record.mood === mood);
}

/**
 * The media address of a reviewed recording (Commons' documented file redirect, which may redirect to the
 * file host). Built from the reviewed file name only. Real-host playback has not been verified.
 */
export function mediaUrl(record: MusicRecording): string {
  return `${MEDIA_ORIGIN}/wiki/Special:FilePath/${encodeURIComponent(record.mediaFile)}`;
}
