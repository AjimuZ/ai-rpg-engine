/**
 * Session scoping above the runtime-neutral task port (P08.3, ADR 0007, docs/06 section 7).
 *
 * <p>Every inference task runs in the logical session of one
 * {@link org.rpgengine.inference.session.SessionScope}: campaign, campaign recovery generation,
 * {@link org.rpgengine.inference.session.AiRole role}, {@link org.rpgengine.inference.session.Audience
 * audience} and the pinned content and policy hashes. {@link org.rpgengine.inference.session.ScopedSessions}
 * maps each scope to its own session identifier and is the only submitter of the port, so callers
 * name a scope and never a session. Since P08.6 the registry's only caller is the fair
 * {@link org.rpgengine.inference.scheduling.InferenceScheduler}, which decides when a scope's next
 * task may start. The role matrix forbids player-facing roles (interpreter,
 * narrator, guide) in the privileged audience, which is where planners, directors, reviewers and
 * content or encounter proposers run; a privileged conversation therefore can never render player
 * text, and its earlier context never reaches a public task through a reused runtime conversation.
 *
 * <p>Renewal (P09.4, docs/08 section 7): the registry counts turns, completed tasks and the
 * estimated conversation size of every live session and renews a line at its next task when the
 * {@link org.rpgengine.inference.session.RenewalPolicy} turn ceiling or context-pressure threshold
 * is reached; a turn that ran but did not complete retires its session at once; the owner can
 * renew an idle campaign. A renewed line's first task carries the continuity block of a
 * {@link org.rpgengine.inference.session.Rehydrator} (implemented in the memory package), and every
 * opening, use and retirement is recorded through a
 * {@link org.rpgengine.inference.session.SessionJournal} ({@code ai_session_record}, V17).
 *
 * <p>What this package does not do: it does not filter context (callers assemble audience-filtered
 * input from typed projections, P08.5 and P09.2; the rehydrator assembles for the session's own
 * audience), it does not detect paraphrased secrets in output (a human-rubric evaluation), and it
 * never resumes a runtime conversation after a restart: the registry stays in memory and the
 * durable records are history and provenance only. Nothing here names a runtime or holds
 * canonical authority; the only storage access is the journal's own table.
 */
package org.rpgengine.inference.session;
