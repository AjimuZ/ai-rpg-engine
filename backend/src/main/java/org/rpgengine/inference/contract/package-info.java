/**
 * Typed application task contracts above the runtime-neutral task port (P08.2, docs/21).
 *
 * <p>Two contracts exist: {@link org.rpgengine.inference.contract.IntentContract} interprets one
 * free-text player declaration into at most one typed intention, a focused clarification or a
 * cannot-complete result, and {@link org.rpgengine.inference.contract.NarrationContract} turns a
 * public resolved-event bundle into attributed narration passages plus at most three editable
 * quick-reply suggestions. Each contract owns a versioned English instruction template, a bundled
 * JSON output schema (constraining generation only) and an independent Java validator that
 * performs the structural and semantic checks; the schema is never trusted as validation.
 *
 * <p>{@link org.rpgengine.inference.contract.ContractRunner} submits one contract task through
 * {@link org.rpgengine.inference.NarrationTaskPort}, validates the untrusted output and allows
 * exactly one bounded repair with the same canonical context. Every result keeps the presentation
 * binding (locale, locale revision, glossary revision) it was generated for, so a caller can
 * discard output that became stale. Nothing in this package mutates state, touches the database,
 * publishes text or names a runtime: an accepted intent still goes through ordinary command
 * admission and rule validation, and accepted narration is published only by P08.5.
 */
package org.rpgengine.inference.contract;
