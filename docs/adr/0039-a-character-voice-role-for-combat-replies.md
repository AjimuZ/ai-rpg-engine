# ADR 0039: A character-voice role for combat replies

- Status: Accepted; implemented in P12.4-B (14 November 2026) as a contract and a pure presentation decision that no gameplay path calls yet. Changes no decision of ADRs 0007, 0029 or 0033.
- Date: 14 November 2026
- Builds on: [ADR 0007](0007-audience-isolation.md) (audience-scoped knowledge), [ADR 0029](0029-initiative-rounds-activations-and-bounded-combat-dialogue.md) (bounded speech and declared social actions), [ADR 0033](0033-negotiation-explicit-answers-and-surrender.md) (offers bind nobody until an explicit answer).

## Context

Docs/21 section 8 asks that combat conversation replies be attributed to their known speaker and grounded in that recipient's authorized knowledge plus the resolved dialogue or social outcome. They must not manufacture acceptance, compliance, movement, a new participant or the end of a fight. When dialogue generation is unavailable it must never hold a transaction, advance the scheduler or hide committed combat state.

The existing roles do not fit. The `narrator` is player-facing but runs only in the `public` audience, whose session accumulates everything the player knows, including things an opponent never saw. The `tactician` runs in one character's audience but is not player-facing, so its output may never be shown. The `guide` is player-facing and character-scoped but is the guide persona's role (P15).

## Decision

1. **A new player-facing role, `character-voice`, permitted only in the `character` audience.** The `combat-reply` task kind runs in it, in the replying participant's own `character:<subject>` audience (`actor:<uuid>`, or `combatant:<encounter>:<rosterId>`). It shares no session with the narrator, with the same character's tactician or with any other character. Like every player-facing role, it can never be privileged.
2. **The context is read from the committed encounter.** It holds the speaker's own public standing, the participants it can see under the same sight rule as tactic choices, the words addressed to it, the public outcome statements and optional beliefs. The guard is completed from the same state with the roster ID of every participant hidden from the speaker and every other participant's campaign actor.
3. **The reply is presentation only.** Its body is `speech`, `narration`, `references` and `reasonCode`. No field can name an action, answer an offer, pay, move or end a fight. Every outcome statement must be cited, and every number must already be in the supplied text.
4. **Failure is silence.** `CombatReplyFallback.decide` shows an accepted reply only while the campaign is still at the revision it was grounded in. Every other outcome shows no generated line and no notice, because the committed events' rule-driven sentences already answer the player. The decision takes no encounter and writes nothing.

## Alternatives considered

- Run replies in the `narrator`'s public session with a speaker-filtered context: rejected. The session's earlier turns hold the player's whole public knowledge, so the reply would not be grounded in the speaker's knowledge alone.
- Reuse the `tactician` role: rejected. It is not player-facing, and mixing a character's tactics with its spoken words in one conversation would let one shape the other.
- Reuse the `guide` role: rejected. It is the guide persona's role, with its own future rules (P15).
- Show a notice when no reply is generated, as tactic fallbacks do: rejected for now. A missing reply does not change what happened or what the player may do (docs/11 section 12).

## Consequences

- `AiRole` gains `CHARACTER_VOICE`; `TaskKind` gains `COMBAT_REPLY` (interactive, 30 s provisional budget). `SessionScopeTest`'s list of player-facing roles now includes it. No migration: stored role names already accept `character-voice`.
- No command, presentation job, route or page calls the contract yet, so no reply is generated, stored, sent or shown. Wiring it in, storing a shown reply and showing it in the combat chat are later work.
- Limits: a roster ID shorter than four characters cannot be a guard marker; an outcome statement naming a participant hidden from the speaker refuses the whole context; paraphrased secrets and an implied acceptance in free words are not detectable mechanically.

## Verification

`CombatReplyContractTest`, `CombatReplyFallbackTest`, `ContractSchemaTest` and `SessionScopeTest`. See the [roadmap entry](../16-development-roadmap.md#16-p12--combat-interface-and-ai-tactics) for the recorded results and what was not proven.
