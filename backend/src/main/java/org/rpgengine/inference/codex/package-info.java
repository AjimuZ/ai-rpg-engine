/**
 * Codex app-server adapter of the runtime-neutral task port (P08.1, docs/06). Contents:
 * <ul>
 *   <li>wires: {@code StdioFrameWire} (owned child, line framing, redacted stderr, bounded
 *       shutdown) and {@code UnixSocketFrameWire} (RFC 6455 client over the packaged private
 *       Unix socket);</li>
 *   <li>{@code RpcGeneration}: one connection with a single reader, monotonic request
 *       identifiers, a bounded pending-request registry, server-request denial, early-event
 *       buffering and exact (thread, turn) inboxes;</li>
 *   <li>{@code CodexProtocol}: request builders and validators for the pinned 0.164.0 shapes
 *       retained in {@code tools/runtime_probe/fixtures/0.164.0/neutral-session.json}, and the
 *       turn tracker that enforces the tool-free item policy;</li>
 *   <li>{@code CodexTaskAdapter}: bounded admission queue, the serialized active-turn permit,
 *       session-to-thread bindings, cancellation, deadlines, reconciliation and lifecycle.</li>
 * </ul>
 * Everything here is package-private except what Spring needs; no other package of the engine
 * may import from it (enforced by {@code InferenceBoundaryTest}). This package never touches the
 * database, canonical state or HTTP, and it never forwards runtime free text or stderr content.
 */
package org.rpgengine.inference.codex;
