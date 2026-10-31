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
 * <p>Not built here: Spring wiring, any real GPU reading, any real image or speech worker, and any link
 * to the task scheduler or the adapters. Everything is tested against scripted servers, so nothing yet
 * proves that the host stays out of memory trouble.
 */
package org.rpgengine.inference.admission;
