/**
 * Owner assignment of the four model slots above the runtime-neutral task port (P21.3-A, ADR 0021
 * decisions 2 and 8, docs/34 sections 4, 11 and 12).
 *
 * <p>{@link org.rpgengine.inference.slots.SlotSettingsService} saves an assignment for
 * {@code text.light}, {@code text.standard}, {@code text.deliberate} or {@code image}, derives its
 * billing and privacy {@link org.rpgengine.inference.slots.BackendClass class}, refuses a class
 * change that the owner did not acknowledge, records readiness probes, and reports the visible
 * state of every slot. {@link org.rpgengine.inference.slots.PersistedSlotAssignments} offers the
 * stored text assignments to the profile resolver.
 *
 * <p>Fallbacks are configuration, never failover: {@link org.rpgengine.inference.slots.SlotFallback}
 * considers only candidates the owner listed, only within the primary's class, and ends in an
 * explicit refusal when none is available. Nothing here sends task content to a backend or chooses a
 * model by itself.
 */
package org.rpgengine.inference.slots;
