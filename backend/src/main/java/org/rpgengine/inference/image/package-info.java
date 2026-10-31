/**
 * Adapter of the neutral image-generation port for an owner-run Forge or A1111 server (P21.5-B.2, ADR 0021
 * decision 1, docs/34 section 8). Contents:
 * <ul>
 *   <li>{@code A1111Settings}: the gateway origin, the destination identifier and the time bounds;</li>
 *   <li>{@code A1111ImageAdapter}: the single-threaded job queue, the contention probe, txt2img, interrupt
 *       and the fixed failure codes;</li>
 *   <li>{@code PngCheck}: structural validation of a received PNG and removal of its metadata chunks;</li>
 *   <li>{@code BoundedBody}: a response body subscriber with a size limit.</li>
 * </ul>
 * Everything here is package-private; no other package of the engine may import from it (enforced by
 * {@code InferenceBoundaryTest}), and nothing constructs the adapter outside tests yet: the runtime image
 * jobs of P17.6 will select it. This package never touches the database or canonical state and receives
 * no game state: its only input is an {@code ImageRequest}.
 */
package org.rpgengine.inference.image;
