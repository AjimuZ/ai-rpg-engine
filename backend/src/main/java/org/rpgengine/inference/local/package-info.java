/**
 * Adapter of the runtime-neutral task port for the packaged llama.cpp model server (P21.3-B, ADR 0021
 * decisions 1 and 5, docs/34 section 7). Contents:
 * <ul>
 *   <li>{@code LocalSettings}: validated {@code engine.inference.local.*} settings;</li>
 *   <li>{@code LocalProtocol}: the request builder and strict parsers of the HTTP chat API;</li>
 *   <li>{@code BoundedBody}: a response body subscriber with a size limit;</li>
 *   <li>{@code LocalTaskAdapter}: bounded admission, one request at a time, per-session
 *       conversations, cancellation and deadlines;</li>
 *   <li>{@code LocalConfiguration}: selects the adapter and the slot routing port when a server URL
 *       is configured.</li>
 * </ul>
 * Everything here is package-private; no other package of the engine may import from it (enforced by
 * {@code InferenceBoundaryTest}). This package never touches the database or canonical state, and it
 * never forwards server free text. Its output is untrusted text, validated by the same contract
 * runner as every other adapter's output.
 */
package org.rpgengine.inference.local;
