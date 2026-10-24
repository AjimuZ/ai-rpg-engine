# ADR 0017 — Bounded pack semantics, compatibility checks and a generation gate

- Status: Accepted; implemented in P04.3 (compiler and gate verified by unit tests, runtime callers pending)
- Date: 24 October 2026
- Owner direction: declarative adventures ([ADR 0004](0004-declarative-adventures.md)), fixed author constraints that generated gap filling cannot override

## Context

P04.2 checked definition shapes and references but rejected expressions, effect graphs, quests and version ranges. Packs need formulas and conditional effects without becoming programs. The same ID and version must always mean the same bytes. Authors also need a way to state facts and entities that later generated content cannot replace. The [adventure pack specification](../09-adventure-packs.md) sections 7, 8 and 10 describe these requirements.

## Decision

Use a closed, data-only expression language analyzed statically at import, not evaluated there. Expressions are integer/boolean constants or single-key operator/atom objects. There are no loops, functions, recursion or host code. The analyzer derives a conservative integer interval per node, so it can prove the absence of overflow and of divisors containing zero, and can bound effect amounts. Limits are depth 12, 200 nodes, 40 dice per expression, constants within ±1,000,000 and intermediates within ±10⁹. Actor-scope expressions may roll dice. World-scope quest predicates may not, so quest progress never depends on randomness. Runtime evaluation belongs to the rules engine (P11).

Effects are typed declarative nodes. Conditional effects nest at most 4 levels, and each top-level list holds at most 20 nodes. Nested effects inherit their conditional's `pre`/`post` timing and may not contradict it: one branch resolving at a different time than its condition would be hard to explain. Ability prerequisites, conditions applied by condition ticks and unconditional quest-status requirements (outside multi-way `any` and `not`) form dependency graphs, and any cycle is a `CYCLE` error that lists the path. A quest reading its own objectives is not an edge, because that is normal progress tracking.

Compatibility is checked against one `EngineContract` value: engine 0.1.0, ruleset `skirmish` 1.0.0, profile `standard`, no modules. The ruleset version is provisional until P07 defines the real ruleset. Ranges use a small SemVer 2.0.0 comparator grammar of 1..4 space-separated comparators with an implicit `=`. There are no `^`/`~`/`||` forms, and prerelease versions compare by plain SemVer precedence without npm's same-tuple restriction (`>=1.0.0 <2.0.0` admits `2.0.0-rc.1`; authors exclude prereleases explicitly). Dependencies pin exact versions and hashes. The `DependencyCatalog` reports installed hashes, and a mismatch for the same ID/version is `HASH_CONFLICT`.

The generation policy file compiles into an immutable `GenerationPolicy`. Every category is disabled unless it is listed. Constraints may name only this pack's definitions, and a fixed field must already be authored (an unknown name is a typo, not a reservation). Excluded tags that conflict with allowed tags or required entities are `CONSTRAINT_CONFLICT`. A pure `GenerationGate` is the single admission check. New entities are bounded by mode, budget, tags, templates, ranges and authored references. Gap filling may only add fields the author left open and never fixed ones. Required entities cannot be retired.

Location reachability from the entrypoint is a warning (`UNREACHABLE_LOCATION`), not an error. Locked links count as traversable and move effects are ignored, so it is a heuristic.

## Alternatives and tradeoffs

- An embedded scripting language would be more expressive but could not be bounded or explained statically; rejected.
- Full npm range syntax would add ambiguity for little author value; the small grammar can be extended compatibly later.
- Enforcing constraints only in prompts would let narration override authored facts; the deterministic gate makes violations rejectable data.
- Treating unreachable locations as errors would reject valid packs that reach places through effects or scripted movement.

## Consequences and verification

`PackCompilerTest` covers expression limits, conditional effects, version parsing and compatibility, hash conflicts, cycles, quests, policy compilation, constraint conflicts, the gate and the reachability warning. Encounter, narrative anchor and progression kinds remain `UNSUPPORTED_KIND`. Scene generation (P10/P12) must call the gate before persisting generated content. P07 must replace the provisional ruleset contract, and P04.4 still owns import reports and registration.
