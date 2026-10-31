package org.rpgengine.inference.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.rpgengine.inference.scheduling.WorkClass;

/**
 * Shared test vocabulary of the admission policy tests: a 100/100 budget, a 60-second lease and aging
 * step, and requests that remember a readable name. Models are sized so that two large ones never
 * fit together.
 */
final class AdmissionFixtures {
    static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration AGING = Duration.ofSeconds(60);
    static final Demand CAPACITY = new Demand(100, 100);
    static final Demand LARGE = new Demand(60, 10);
    static final Demand MEDIUM = new Demand(50, 20);
    static final Demand SMALL = new Demand(10, 5);
    static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID C = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    private final Map<UUID, String> names = new HashMap<>();

    static AdmissionSettings settings(Demand capacity, int textBatch) {
        return new AdmissionSettings(capacity, TTL, AGING,
                Map.of(Modality.TEXT, textBatch, Modality.IMAGE, 1, Modality.SPEECH, 4));
    }

    static AdmissionSettings settings() {
        return settings(CAPACITY, 4);
    }

    AdmissionRequest request(String name, Modality modality, WorkClass workClass, UUID owner, String model,
            Demand demand, Instant enqueuedAt) {
        AdmissionRequest request = new AdmissionRequest(UUID.randomUUID(), modality, workClass, owner, model, demand,
                enqueuedAt);
        names.put(request.id(), name);
        return request;
    }

    AdmissionRequest text(String name, WorkClass workClass, UUID owner, String model, Demand demand) {
        return request(name, Modality.TEXT, workClass, owner, model, demand, T0);
    }

    String name(AdmissionRequest request) {
        return names.get(request.id());
    }

    List<String> names(Grant grant) {
        return grant.admitted().stream().map(this::name).toList();
    }
}
