package org.jarvis.syncservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against a regression where {@code /actuator/prometheus} silently disappears
 * (HTTP 404) because it fell out of {@code management.endpoints.web.exposure.include},
 * or because the sync-service's device-facing security chain (see
 * {@link SyncSecurityConfig}) stopped permitting it unauthenticated. Prometheus scrapes
 * this endpoint anonymously (see infra/k8s prod scrape annotations on port 8095), so a
 * 401/403/404 here breaks metrics collection for the whole service.
 *
 * <p>{@code @AutoConfigureObservability} is required because {@code @SpringBootTest}
 * disables metrics/observability export by default (Spring Boot Test's
 * {@code ObservabilityContextCustomizerFactory} sets
 * {@code management.defaults.metrics.export.enabled=false} for every test unless
 * explicitly opted back in) — without it, {@code /actuator/prometheus} 404s in-test
 * regardless of what the real application.yml says.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456"
})
class ActuatorPrometheusExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Test
    void managementExposureIncludesPrometheusAndHealth() {
        String include = environment.getProperty("management.endpoints.web.exposure.include");

        assertThat(include).isNotNull();
        assertThat(include.split(",")).contains("prometheus", "health");
    }

    @Test
    void actuatorPrometheusIsReachableUnauthenticatedAndReturnsMetrics() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    void actuatorHealthIsReachableUnauthenticated() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
