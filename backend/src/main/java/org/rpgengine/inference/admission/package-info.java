/**
 * The shared GPU and RAM admission coordinator for text, image and speech models (P21.4-A, docs/34
 * section 13). Local backends compete for one pool of GPU and RAM; this package decides which
 * request may make its model resident next, so the engine never asks the host for more than it was
 * given.
 *
 * <p>{@link org.rpgengine.inference.admission.AdmissionPolicy} is the pure policy: strict priority
 * between interactive and background work, background aging, fair rotation across modalities and
 * owners, one {@link org.rpgengine.inference.admission.Lease lease} per resident model shared by a
 * batch of compatible requests, expiry, and the choice of leases to take back for urgent work.
 * {@link org.rpgengine.inference.admission.AdmissionCoordinator} runs it over a
 * {@link org.rpgengine.inference.admission.LeaseStore} (PostgreSQL V29 {@code resource_lease}) with
 * an injected clock, so a restart never forgets capacity that may still be in use.
 *
 * <p>{@link org.rpgengine.inference.admission.LeaseExecutor} (P21.4-B) turns those leases into real
 * residency over a {@link org.rpgengine.inference.ModelServerControl}: the slot save, unload,
 * freed-memory check, batch, reload and slot restore, interruption between the steps of a
 * {@link org.rpgengine.inference.admission.SteppedJob}, contention from processes the engine does not
 * own through a {@link org.rpgengine.inference.admission.GpuMemoryReading}, and metrics.
 *
 * <p>{@link org.rpgengine.inference.admission.HostGpuMemoryReading} reads free memory from
 * {@code nvidia-smi}, and {@code AdmissionConfiguration} builds the
 * coordinator and the executor only when {@code engine.inference.admission.enabled} is {@code true}.
 * Behind the same switch {@link org.rpgengine.inference.admission.TextResidencyDriver} runs the executor
 * on its own thread and serves the scheduler's text permit through the neutral
 * {@link org.rpgengine.inference.scheduling.TextResidency}. {@link org.rpgengine.inference.admission.ImageAdmission}
 * is how an image job reaches its adapter: behind the switch it waits for a lease and runs as an
 * {@link org.rpgengine.inference.admission.ImageSteppedJob}, on a grant the driver passes on; without the switch
 * the adapter is called directly.
 *
 * <p>The image control is the neutral {@link org.rpgengine.inference.ImageModelControl}, which only the packaged worker's
 * address provides; without it an image batch ends {@code LOAD_FAILED}. {@code ReadingWindow} is the executor's source of
 * expected text demand, opened by each finished player turn. Not built here: a speech worker or its control, and any
 * caller of the image path. Everything is tested against scripted servers, so nothing yet proves that the host stays out
 * of memory trouble.
 */
package org.rpgengine.inference.admission;
