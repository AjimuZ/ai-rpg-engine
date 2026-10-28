/**
 * Optional bounded quest planning (P08.7, docs/28 sections 4 to 7, ADR 0014): the snapshot
 * assembler of the privileged quest planner, the planning service that requests, runs and
 * selectively replans versioned quest-plan artifacts through the fair inference scheduler and
 * the transactional plan-admission gate, the baseline/prepared standing lookup and the optional
 * background worker. Planning is off by default and never on the path of a player action: the
 * playable baseline (ordinary commands, deterministic resolution, canonical save and grounded
 * public narration) neither waits for nor reads anything produced here, and a validated plan is
 * still only preparation that an ordinary command must recheck before using one branch.
 */
package org.rpgengine.planning;
