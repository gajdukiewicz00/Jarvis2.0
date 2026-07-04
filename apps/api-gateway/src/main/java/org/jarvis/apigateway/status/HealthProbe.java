package org.jarvis.apigateway.status;

/**
 * Minimal, fast reachability probe for a downstream Spring Boot service.
 *
 * <p>Abstracted as an interface so the {@link StatusReportService} can be unit
 * tested without any network. The default implementation is
 * {@link HttpHealthProbe}.</p>
 */
public interface HealthProbe {

    /**
     * @param baseUrl service base URL, e.g. {@code http://llm-service:8091}
     * @return {@code true} when the service answers its actuator health endpoint
     *         with a 2xx status inside the configured timeout
     */
    boolean isHealthy(String baseUrl);
}
