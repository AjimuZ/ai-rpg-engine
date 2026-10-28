/**
 * Session scoping above the runtime-neutral task port (P08.3, ADR 0007, docs/06 section 7).
 *
 * <p>Every inference task runs in the logical session of one
 * {@link org.rpgengine.inference.session.SessionScope}: campaign, campaign recovery generation,
 * {@link org.rpgengine.inference.session.AiRole role}, {@link org.rpgengine.inference.session.Audience
 * audience} and the pinned content and policy hashes. {@link org.rpgengine.inference.session.ScopedSessions}
 * maps each scope to its own session identifier and is the only submitter of the port, so callers
 * name a scope and never a session. The role matrix forbids player-facing roles (interpreter,
 * narrator, guide) in the privileged audience, which is where planners, directors, reviewers and
 * content or encounter proposers run; a privileged conversation therefore can never render player
 * text, and its earlier context never reaches a public task through a reused runtime conversation.
 *
 * <p>What this package does not do: it does not filter context (callers assemble audience-filtered
 * input from typed projections, P08.5 and P09.2), it does not detect paraphrased secrets in output
 * (a human-rubric evaluation), and it keeps no durable session records or renewal counters
 * (P09.3, P09.4). Nothing here names a runtime, touches the database or holds canonical authority.
 */
package org.rpgengine.inference.session;
