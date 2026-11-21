/**
 * Maps the player-safe public combat projection to a music mood through a pack's mood policy (P17.2-B, P17.2-C).
 *
 * The only input is {@link CombatView}, which already holds nothing the player has not observed, so hidden
 * enemies, private motives and future encounters cannot change the music. A policy chooses a mood per closed
 * public state; it cannot name an event, an entity or a state outside {@link PUBLIC_STATES}, so it cannot be used to
 * react to hidden state. No network call is made.
 */
import { CombatView } from '../campaign/campaign-protocol';
import { Mood, MOODS } from './music-catalog';

/** The public states a policy distinguishes; a policy key outside this list is refused. */
export const PUBLIC_STATES = ['quiet', 'offered', 'fight', 'won', 'lost', 'settled'] as const;
export type PublicState = (typeof PUBLIC_STATES)[number];

/** The mood chosen for every public state. */
export type MoodPolicy = Readonly<Record<PublicState, Mood>>;

/** The policy of a pack that declares none, equal to the mapping delivered by P17.2-B. */
export const DEFAULT_MOOD_POLICY: MoodPolicy = {
  quiet: 'exploration', offered: 'tension', fight: 'combat', won: 'victory', lost: 'defeat', settled: 'exploration',
};

/**
 * The public state of one projection.
 *
 * - a fight in progress is `fight`; a resolved fight is `won` or `lost` for the outcomes that name one and `settled`
 *   for an escape or stalemate;
 * - outside a fight, an offered encounter (observably present participants) is `offered`;
 * - everything else, including a state not read yet (null), is `quiet`.
 */
export function publicStateOf(view: CombatView | null): PublicState {
  const encounter = view?.encounter ?? null;
  if (encounter !== null) {
    switch (encounter.outcome?.outcome) {
      case undefined: return 'fight';
      case 'VICTORY': case 'OBJECTIVE': case 'HOSTILE_SURRENDER': case 'AGREEMENT': return 'won';
      case 'DEFEAT': case 'PLAYER_SURRENDER': return 'lost';
      default: return 'settled';
    }
  }
  return (view?.offers.length ?? 0) > 0 ? 'offered' : 'quiet';
}

/** The mood of one public state under a policy (the default policy when none is given). */
export function moodOf(view: CombatView | null, policy: MoodPolicy = DEFAULT_MOOD_POLICY): Mood {
  return policy[publicStateOf(view)];
}

/**
 * Read a pack's declared mood policy.
 *
 * @param raw an untrusted object mapping public states to moods
 * @returns the declared moods over the defaults, or null when `raw` is not a plain object, names a key outside
 *   {@link PUBLIC_STATES} or a value that is not a known mood. Nothing is repaired: an invalid policy is dropped
 *   whole and the caller keeps {@link DEFAULT_MOOD_POLICY}.
 */
export function parseMoodPolicy(raw: unknown): MoodPolicy | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const declared = raw as Record<string, unknown>;
  const policy: Record<PublicState, Mood> = { ...DEFAULT_MOOD_POLICY };
  for (const key of Object.keys(declared)) {
    const value = declared[key];
    if (!(PUBLIC_STATES as readonly string[]).includes(key) || typeof value !== 'string' || !(MOODS as readonly string[]).includes(value)) return null;
    policy[key as PublicState] = value as Mood;
  }
  return policy;
}
