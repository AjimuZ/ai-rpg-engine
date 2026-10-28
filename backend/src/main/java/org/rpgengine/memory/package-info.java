/**
 * Campaign memory: checkpoints (P03, P09.3), stamped, budgeted AI task contexts (P09.2), continuity
 * summaries (P09.3), session rehydration (P09.4), restart reconciliation and the resume card (P09.5).
 *
 * <p>{@link org.rpgengine.memory.ContextAssembler} reads one audience's authorized records in a
 * single snapshot, ranks them by explicit IDs, causal links, per-language full text and recency,
 * and selects them under a {@link org.rpgengine.memory.ContextBudget} through the pure
 * {@link org.rpgengine.memory.ContextSelection}. Mandatory material is never truncated: it
 * either fits or yields a controlled context-too-large result. Every item carries an epistemic
 * standing, so beliefs, narration and private planning assumptions are never presented as
 * admitted facts. {@link org.rpgengine.memory.ContextEnvelope} stamps provenance, language and
 * glossary revisions, binds glossary terms ({@link org.rpgengine.memory.PackGlossaries}) and
 * digests the exact rendering.
 *
 * <p>P09.3 adds the continuity layer: {@link org.rpgengine.memory.OperationalCheckpoints} keeps a
 * deterministic, hash-verified snapshot of canonical state at safe boundaries;
 * {@link org.rpgengine.memory.MemorySummaries} requests, bounds, records and invalidates
 * audience-scoped, source-linked continuity summaries and computes their freshness;
 * {@link org.rpgengine.memory.ContinuitySummarizer} runs one summary job through the fair scheduler;
 * and {@link org.rpgengine.memory.MemoryWorker} drives both in bounded background passes. A usable
 * summary enters contexts as an optional, labelled {@code summary} item that current state always
 * overrides. Restore semantics arrived with P03.5.
 *
 * <p>P09.4 adds renewal support: {@link org.rpgengine.memory.SessionRehydration} builds the
 * continuity block that the first task of a renewed AI session carries (resume point, verified
 * checkpoint, audience envelope with the usable summary), and
 * {@link org.rpgengine.memory.SessionRenewalService} with its controller lets the owner renew an
 * idle campaign's sessions. Neither writes canonical state.
 *
 * <p>P09.5 adds the resume protocol of docs/08 section 10: {@link org.rpgengine.memory.MemoryReconciliation}
 * reconciles derived memory after a restart (summary jobs, pinned content diagnosis, unusable
 * checkpoints replaced from canonical state, summaries of a diverged or rolled-back history
 * invalidated, stale summaries replaced, narration still owed counted), started once by
 * {@link org.rpgengine.memory.MemoryStartupReconciliation} before HTTP; and
 * {@link org.rpgengine.memory.ResumeCards} with its controller serves the short factual resume
 * card, read from committed state without advancing time. Neither writes canonical state.
 */
package org.rpgengine.memory;
