/**
 * The durable fair priority queue in front of every inference task (P08.6, docs/06 section 12,
 * docs/28 section 9). {@link org.rpgengine.inference.scheduling.InferenceScheduler} admits
 * {@link org.rpgengine.inference.scheduling.ScheduledTask scheduled tasks} into the durable
 * {@link org.rpgengine.inference.scheduling.TaskLedger} (V14 {@code inference_task}), chooses the
 * next one with the pure {@link org.rpgengine.inference.scheduling.FairQueue} policy (interactive
 * first, isolated role/audience lines in order, background aging, fair campaign rotation and the
 * interactive reservation), dispatches it in its scope's session through
 * {@link org.rpgengine.inference.session.ScopedSessions} and records its terminal outcome.
 *
 * <p>The scheduler owns the installation's runtime turn permit (one, as P01.7 selected; the V14
 * single-turn index enforces it across processes), bounded queue places, rolling usage quotas, the
 * durable account backoff, preemption of background work, cancellation by exact handle,
 * reconciliation of unconfirmed outcomes and restart recovery. It never stores instructions or
 * outputs, never replays captured requests and holds no canonical authority: results are untrusted
 * proposals that the contracts validate.
 */
package org.rpgengine.inference.scheduling;
