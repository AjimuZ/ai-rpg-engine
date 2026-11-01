/**
 * Adapters of the neutral image-generation port for an owner-run Forge or A1111 server (P21.5-B.2, ADR 0021
 * decision 1, docs/34 section 8) and for the packaged image worker (P21.4-D, docs/34 section 10). Contents:
 * <ul>
 *   <li>{@code A1111Settings}: the gateway origin, the destination identifier and the time bounds;</li>
 *   <li>{@code A1111ImageAdapter}: the single-threaded job queue, the contention probe, txt2img, interrupt
 *       and the fixed failure codes;</li>
 *   <li>{@code WorkerSettings} and {@code WorkerClient}: the packaged worker's address, time bounds and HTTP exchange;</li>
 *   <li>{@code WorkerModelControl}: the worker's load and unload routes as a {@code ModelServerControl};</li>
 *   <li>{@code WorkerImageAdapter}: the worker's generate and cancel routes as the port;</li>
 *   <li>{@code WorkerImageConfiguration}: both worker types, only with the worker's address and the admission
 *       switch;</li>
 *   <li>{@code PngCheck}: structural validation of a received PNG and removal of its metadata chunks;</li>
 *   <li>{@code BoundedBody}: a response body subscriber with a size limit.</li>
 * </ul>
 * Everything here is package-private; no other package of the engine may import from it (enforced by
 * {@code InferenceBoundaryTest}). Nothing constructs the {@code a1111-http} adapter outside tests yet, and nothing calls
 * the worker's port: the runtime image jobs of P17.6 will select one. This package never touches the database or
 * canonical state and receives no game state: its only input is an {@code ImageRequest}.
 */
package org.rpgengine.inference.image;
