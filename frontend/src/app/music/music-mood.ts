/**
 * Maps the player-safe public combat projection to a music mood (P17.2-B).
 *
 * The only input is {@link CombatView}, which already holds nothing the player has not observed, so hidden
 * enemies, private motives and future encounters cannot change the music. No network call is made.
 */
import { CombatView } from '../campaign/campaign-protocol';
import { Mood } from './music-catalog';

/**
 * The mood of one public state.
 *
 * - a fight in progress is `combat`; a resolved fight is `victory` or `defeat` for the outcomes that name one
 *   and `exploration` for an escape or stalemate;
 * - outside a fight, an offered encounter (observably present participants) is `tension`;
 * - everything else, including a state not read yet (null), is `exploration`.
 */
export function moodOf(view: CombatView | null): Mood {
  const encounter = view?.encounter ?? null;
  if (encounter !== null) {
    switch (encounter.outcome?.outcome) {
      case undefined: return 'combat';
      case 'VICTORY': case 'OBJECTIVE': case 'HOSTILE_SURRENDER': case 'AGREEMENT': return 'victory';
      case 'DEFEAT': case 'PLAYER_SURRENDER': return 'defeat';
      default: return 'exploration';
    }
  }
  return (view?.offers.length ?? 0) > 0 ? 'tension' : 'exploration';
}
