/**
 * Runtime-neutral narration task port (P08.1, ADR 0020 and ADR 0021). Application services
 * submit bounded inference tasks to a {@link org.rpgengine.inference.NarrationTaskPort} with a
 * logical {@link org.rpgengine.inference.InferenceSlot} ({@code light}, {@code standard} or
 * {@code deliberate}), a logical session identifier, instructions and a JSON output schema, and
 * receive an untrusted {@link org.rpgengine.inference.TaskResult}. Nothing here names a runtime
 * protocol, process, socket, thread or turn: those belong to one adapter per runtime (the Codex
 * adapter in the {@code codex} subpackage and the model server adapter in the {@code local}
 * subpackage), which no other package may reference. When both can run,
 * {@link org.rpgengine.inference.SlotRoutingTaskPort} chooses by each task's slot assignment.
 *
 * <p>Engine code does not call the port directly: the {@code session} subpackage maps each
 * campaign, generation, role, audience, content and policy scope to its own logical session and
 * is the port's only submitter (P08.3), so privileged and public work never share a conversation.
 *
 * <p>Authority is unchanged by this package: a result is a proposal or prose candidate that
 * later validators must admit (P08.2). Slot assignments are private owner settings resolved by
 * {@link org.rpgengine.inference.SlotAssignments}; when no runtime is configured the engine
 * wires {@link org.rpgengine.inference.UnavailableTaskPort}, so every task ends UNAVAILABLE and
 * the deterministic game keeps working.
 */
package org.rpgengine.inference;
