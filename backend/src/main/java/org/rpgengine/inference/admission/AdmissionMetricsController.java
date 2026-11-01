package org.rpgengine.inference.admission;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only, read-only HTTP view of {@link LeaseExecutor#metrics()}: {@code GET /api/v1/models/admission-metrics}.
 * The route exists only while {@code engine.inference.admission.enabled} is {@code true}; with the switch off no
 * bean is built and the path has no mapping. The path sits under {@code /api/v1/models/} so that
 * {@link LocalHttpAdmission} admits the owner and refuses everyone else before this class runs; a request
 * without the owner attribute is refused here as well.
 *
 * <p>The body is the sorted map of fixed counter and gauge names to numbers, nothing else: no key, endpoint,
 * model name or task content can appear. The read waits while a swap holds the executor's monitor, so it is not
 * lock-free and can be slow during a long load or unload.
 */
@RestController
@Conditional(AdmissionConfiguration.Enabled.class)
public class AdmissionMetricsController {
    private final LeaseExecutor executor;

    /** @param executor the executor whose metrics are shown; a missing bean fails startup */
    AdmissionMetricsController(LeaseExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * @param request admitted HTTP request
     * @return 200 with the sorted metrics map as a JSON object; 403 {@code LOCAL_ADMISSION_DENIED} without the owner
     */
    @GetMapping("/api/v1/models/admission-metrics")
    public ResponseEntity<?> metrics(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID)) {
            return ResponseEntity.status(403).body(Map.of("code", "LOCAL_ADMISSION_DENIED"));
        }
        return ResponseEntity.ok(executor.metrics());
    }
}
