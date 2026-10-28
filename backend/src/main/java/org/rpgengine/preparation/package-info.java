/**
 * Durable preparation foundations (docs/28): fenced job leases and attempt history,
 * immutable planning snapshots, versioned plan artifacts with typed dependencies,
 * per-scope query epochs, campaign recovery generations, startup lease recovery, the
 * transactional plan-admission gate (scope, pinned content, author constraints, query
 * epochs and typed row versions) and the activation check used by ordinary canonical
 * commands. Inputs are backend-computed identities, hashes and bounded JSON; outputs are
 * detached records. Prepared data is never canonical truth: nothing here advances state
 * revision, game turn, world time, rewards, draws or event sequence, and no network,
 * runtime account or inference call is made. Runtime dispatch, budgets and the semantic
 * quest-plan contract remain later tasks (P08.6, P08.7).
 */
package org.rpgengine.preparation;
