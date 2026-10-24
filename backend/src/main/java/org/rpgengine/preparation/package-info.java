/**
 * Durable preparation foundations (docs/28): fenced job leases and attempt history,
 * immutable planning snapshots, versioned plan artifacts with typed dependencies,
 * per-scope query epochs, campaign recovery generations and startup lease recovery.
 * Inputs are backend-computed identities, hashes and bounded JSON; outputs are
 * detached records. Prepared data is never canonical truth: nothing here advances
 * state revision, game turn, world time or event sequence, and no network, runtime
 * account or inference call is made. Dispatch, budgets and semantic plan validation
 * remain later tasks (P07.6, P08.6, P08.7).
 */
package org.rpgengine.preparation;
