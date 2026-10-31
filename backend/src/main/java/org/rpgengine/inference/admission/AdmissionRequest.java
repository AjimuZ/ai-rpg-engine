package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.inference.scheduling.WorkClass;

/**
 * One request to make a model resident and use it.
 *
 * <p>Two requests may share a lease only when they name the same modality and model: the lease
 * holds one copy of the model, and its {@code demand} is counted once however many requests share
 * it. The demand of the request that creates the lease is the one reserved.
 *
 * @param id unique request identity
 * @param modality kind of model
 * @param workClass priority class, the same classes the task scheduler uses
 * @param owner fairness owner, normally the campaign the work belongs to
 * @param model model identifier, as stored in V29 {@code resource_lease.model}
 * @param demand capacity the resident model needs
 * @param enqueuedAt submission time, the base of background aging
 */
public record AdmissionRequest(UUID id, Modality modality, WorkClass workClass, UUID owner, String model, Demand demand,
        Instant enqueuedAt) {
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    /** Require every component; a malformed model identifier or an empty demand fails. */
    public AdmissionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modality, "modality");
        Objects.requireNonNull(workClass, "workClass");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        if (model == null || !MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("model identifier is malformed");
        }
        if (demand.isEmpty()) {
            throw new IllegalArgumentException("a model needs some capacity");
        }
    }
}
