/**
 * Runtime-neutral narration task port (P08.1, ADR 0020 and ADR 0021). Application services
 * submit bounded inference tasks to a {@link org.rpgengine.inference.NarrationTaskPort} with a
 * logical {@link org.rpgengine.inference.InferenceSlot} ({@code light}, {@code standard} or
 * {@code deliberate}), a logical session identifier, instructions and a JSON output schema, and
 * receive an untrusted {@link org.rpgengine.inference.TaskResult}. Nothing here names a runtime
 * protocol, process, socket, thread or turn: those belong to one adapter per runtime (today only the
 * Codex adapter in the {@code codex} subpackage), which no other package may reference.
 *
 * <p>Authority is unchanged by this package: a result is a proposal or prose candidate that
 * later validators must admit (P08.2). Slot assignments are private owner settings resolved by
 * {@link org.rpgengine.inference.SlotAssignments}; when no runtime is configured the engine
 * wires {@link org.rpgengine.inference.UnavailableTaskPort}, so every task ends UNAVAILABLE and
 * the deterministic game keeps working.
 */
package org.rpgengine.inference;
