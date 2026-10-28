/**
 * Campaign memory: immutable checkpoints (P03) and stamped, budgeted AI task contexts (P09.2).
 *
 * <p>{@link org.rpgengine.memory.ContextAssembler} reads one audience's authorized records in a
 * single snapshot, ranks them by explicit IDs, causal links, per-language full text and recency,
 * and selects them under a {@link org.rpgengine.memory.ContextBudget} through the pure
 * {@link org.rpgengine.memory.ContextSelection}. Mandatory material is never truncated: it
 * either fits or yields a controlled context-too-large result. Every item carries an epistemic
 * standing, so beliefs, narration and private planning assumptions are never presented as
 * admitted facts. {@link org.rpgengine.memory.ContextEnvelope} stamps provenance, language and
 * glossary revisions, binds glossary terms ({@link org.rpgengine.memory.PackGlossaries}) and
 * digests the exact rendering. Summaries, renewal and rehydration arrive with P09.3–P09.5;
 * restore semantics arrived with P03.5.
 */
package org.rpgengine.memory;
