/**
 * Grounded narration of committed operations (P08.5). This package joins the two neutral layers
 * that must not depend on each other: the presentation jobs of {@code org.rpgengine.presentation},
 * which own durable job state and publication, and the narration contract of
 * {@code org.rpgengine.inference.contract}, which owns rendering, validation and the single repair.
 * {@link org.rpgengine.narration.NarrationContextAssembler} builds the public context from stored
 * public events only, and {@link org.rpgengine.narration.GroundedNarration} runs the contract and
 * returns validated narration, or the outcome that makes the deterministic receipt the
 * presentation. Nothing here writes to the database or names a runtime.
 */
package org.rpgengine.narration;
