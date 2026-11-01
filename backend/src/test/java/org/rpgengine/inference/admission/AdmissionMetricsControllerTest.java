package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.rpgengine.identity.LocalHttpAdmission;
import org.rpgengine.inference.ModelServerControl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;

/** The owner-only metrics route under the opt-in switch, wired by Spring with a stand-in lease store and control. */
class AdmissionMetricsControllerTest {
    private static final long MIB = 1024L * 1024L;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionConfiguration.class, AdmissionMetricsController.class)
            .withBean(LeaseStore.class, MemoryLeaseStore::new)
            .withBean(ModelServerControl.class, () -> mock(ModelServerControl.class))
            .withPropertyValues("engine.inference.admission.gpu-budget-mib=10240");
    private final JsonMapper json = JsonMapper.builder().build();

    private static MockHttpServletRequest owner() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/models/admission-metrics");
        request.setAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE, UUID.randomUUID());
        return request;
    }

    @Test
    void withTheSwitchOnTheOwnerReadsTheSortedMetricsAsJson() {
        runner.withPropertyValues("engine.inference.admission.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AdmissionMetricsController.class);
            ResponseEntity<?> reply = context.getBean(AdmissionMetricsController.class).metrics(owner());
            assertThat(reply.getStatusCode().value()).isEqualTo(200);
            Map<?, ?> body = (Map<?, ?>) reply.getBody();
            assertThat(body).isEqualTo(context.getBean(LeaseExecutor.class).metrics());
            assertThat(new java.util.ArrayList<Object>(body.keySet()))
                    .isEqualTo(new java.util.ArrayList<Object>(new java.util.TreeSet<Object>(body.keySet())));
            var tree = json.readTree(json.writeValueAsString(body));
            assertThat(tree.get("capacity_free_gpu_bytes").asLong()).isEqualTo(10240 * MIB);
            assertThat(tree.get("leases_active").asLong()).isZero();
        });
    }

    @Test
    void withoutTheSwitchNoControllerExists() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AdmissionMetricsController.class)
                    .doesNotHaveBean(LeaseExecutor.class);
        });
        runner.withPropertyValues("engine.inference.admission.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(AdmissionMetricsController.class));
    }

    @Test
    void aRequestWithoutTheOwnerIsRefusedAndReadsNothing() {
        runner.withPropertyValues("engine.inference.admission.enabled=true").run(context -> {
            MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/v1/models/admission-metrics");
            ResponseEntity<?> reply = context.getBean(AdmissionMetricsController.class).metrics(anonymous);
            assertThat(reply.getStatusCode().value()).isEqualTo(403);
            assertThat(reply.getBody()).isEqualTo(Map.of("code", "LOCAL_ADMISSION_DENIED"));
            anonymous.setAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE, "not-a-uuid");
            assertThat(context.getBean(AdmissionMetricsController.class).metrics(anonymous).getStatusCode().value())
                    .isEqualTo(403);
        });
    }

    @Test
    void theRouteIsGetOnlyAtTheModelsPrefix() throws Exception {
        Method method = AdmissionMetricsController.class.getMethod("metrics", jakarta.servlet.http.HttpServletRequest.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping.value()).containsExactly("/api/v1/models/admission-metrics");
        assertThat(method.isAnnotationPresent(RequestMapping.class)).isFalse();
    }
}
