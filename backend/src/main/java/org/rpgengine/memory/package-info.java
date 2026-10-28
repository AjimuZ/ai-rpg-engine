/**
 * Campaign memory: checkpoints (P03, P09.3), stamped, budgeted AI task contexts (P09.2) and continuity
 * summaries (P09.3).
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
 * overrides. Renewal and rehydration arrive with P09.4–P09.5; restore semantics arrived with P03.5.
 */
package org.rpgengine.memory;
