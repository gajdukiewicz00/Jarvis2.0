package org.jarvis.apigateway.agent;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Best-effort propagation of the global panic kill-switch to the orchestrator.
 * The api-gateway has no message broker, so it pushes engage/clear over HTTP to
 * the orchestrator's SVC_INTERNAL control endpoint. Failure to reach the
 * orchestrator never blocks the panic action (the gateway still blocks its own
 * client-facing paths via {@code PanicGuardFilter}).
 */
@Slf4j
@Component
public class PanicPropagator {

    private final RestTemplate restTemplate;
    private final ServiceJwtProvider serviceJwtProvider;
    private final String orchestratorUrl;

    public PanicPropagator(RestTemplateBuilder builder,
                           ServiceJwtProvider serviceJwtProvider,
                           @Value("${services.orchestrator.url:http://orchestrator:8083}") String orchestratorUrl) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
        this.serviceJwtProvider = serviceJwtProvider;
        this.orchestratorUrl = orchestratorUrl;
    }

    public void propagate(boolean engaged, String actor, String reason) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Service-Token", serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL")));
            Map<String, Object> body = Map.of("engaged", engaged, "actor", actor == null ? "api" : actor,
                    "reason", reason == null ? "" : reason);
            restTemplate.postForEntity(orchestratorUrl + "/internal/control/panic",
                    new HttpEntity<>(body, headers), Void.class);
            log.info("panic propagated to orchestrator engaged={}", engaged);
        } catch (RuntimeException e) {
            log.warn("panic propagation to orchestrator failed (best-effort, gateway filter still active): {}",
                    e.getMessage());
        }
    }
}
