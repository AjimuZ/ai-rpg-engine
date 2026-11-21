/**
 * Stable entity artwork lookup, hash URLs, credits and rarity frame tokens for the playthrough panels (P17.1-B, docs/23).
 *
 * Everything here is display data the server already decided: an image is found by its category and stable subject
 * reference (never a display name), served from the campaign's hash route, and a subject without art gets a neutral
 * fallback chosen by the caller. A frame follows the item's validated rarity tier and nothing else, so art can never
 * raise or lower a rarity.
 */
import { ArtworkEntry } from './campaign-protocol';
import { CampaignScope } from './campaign-session';

/** Frame treatment of a rarity tier: a rank for the default tiers, `unranked` for a pack's own tier, `none` without one. */
export type FrameToken = 'none' | 'unranked' | 'rank-1' | 'rank-2' | 'rank-3' | 'rank-4' | 'rank-5';

/** The proposed default tier labels in ascending order (docs/23 section 5); a pack's own tiers fall back to `unranked`. */
const DEFAULT_TIERS = ['common', 'uncommon', 'rare', 'epic', 'legendary'] as const;

/**
 * Choose the frame of a known rarity tier. The result depends only on the tier ID, which is validated item data;
 * the visible tier name is always shown beside the frame, so the frame is never the only carrier of the tier.
 *
 * @param tier declared tier ID of the item, or null when the item has none or it is unknown to the player
 * @returns `none` without a tier, a rank for the default tiers, otherwise `unranked`
 */
export function frameFor(tier: string | null): FrameToken {
  if (tier === null) return 'none';
  const rank = DEFAULT_TIERS.indexOf(tier.toLowerCase() as (typeof DEFAULT_TIERS)[number]);
  return rank < 0 ? 'unranked' : (`rank-${rank + 1}` as FrameToken);
}

/** Image categories shown in the gallery, in display order; item art is shown in the inventory and equipment lists. */
export const GALLERY_CATEGORIES = ['scene', 'map', 'portrait', 'ui'] as const;
export type GalleryCategory = (typeof GALLERY_CATEGORIES)[number];

/** One gallery image: its stable subject reference and the hash URL it is served from. */
export interface GalleryItem { readonly subject: string; readonly url: string; readonly width: number; readonly height: number }

/** The images of one gallery category, ordered by subject. */
export interface GalleryGroup { readonly category: GalleryCategory; readonly items: readonly GalleryItem[] }

/** Lookup of the artwork in force for one campaign scope; an empty index shows only fallbacks. */
export class ArtworkIndex {
  private readonly byKey = new Map<string, ArtworkEntry>();

  /**
   * @param entries the validated listing of one campaign
   * @param scope the campaign the listing belongs to, which also scopes every URL it produces
   */
  constructor(readonly entries: readonly ArtworkEntry[], private readonly scope: CampaignScope) {
    for (const entry of entries) {
      const key = `${entry.category}\u0000${entry.subject}`;
      const known = this.byKey.get(key);
      if (known === undefined || entry.appearanceRevision > known.appearanceRevision) this.byKey.set(key, entry);
    }
  }

  /**
   * URL of the image in force for a subject.
   *
   * @param category artwork category of the subject
   * @param subject stable public reference, such as an item definition ID
   * @returns the hash route URL, or null when the subject has no image (the caller then shows its neutral fallback)
   */
  imageUrl(category: ArtworkEntry['category'], subject: string): string | null {
    const entry = this.byKey.get(`${category}\u0000${subject}`);
    if (entry === undefined || !entry.mediaType.startsWith('image/')) return null;
    return this.url(entry);
  }

  /**
   * The scene, map, portrait and interface images in force, grouped by category in display order. A category without
   * an image is absent, and so is audio, which is never drawn.
   */
  gallery(): readonly GalleryGroup[] {
    const groups: GalleryGroup[] = [];
    for (const category of GALLERY_CATEGORIES) {
      const items = [...this.byKey.values()]
        .filter((entry) => entry.category === category && entry.mediaType.startsWith('image/'))
        .sort((a, b) => (a.subject < b.subject ? -1 : a.subject > b.subject ? 1 : 0))
        .map((entry): GalleryItem => ({ subject: entry.subject, url: this.url(entry), width: entry.width, height: entry.height }));
      if (items.length > 0) groups.push({ category, items });
    }
    return groups;
  }

  /** The hash route URL of an entry, always within the campaign the listing belongs to. */
  private url(entry: ArtworkEntry): string {
    return `/api/v1/campaigns/${this.scope.campaignId}/assets/${entry.sha256}?adventureId=${this.scope.adventureId}`;
  }

  /** Distinct credit lines of the images in force, in first-seen order, for the visible credits. */
  credits(): readonly string[] {
    return [...new Set([...this.byKey.values()].filter((entry) => entry.mediaType.startsWith('image/')).map((entry) => entry.credit))];
  }
}
