package org.jarvis.apigateway.status;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link HealthProbe} that issues a short-timeout GET against
 * {@code <baseUrl>/actuator/health}. Any reachable 2xx is treated as healthy.
 *
 * <p>The probe is deliberately conservative: a single attempt with a small
 * timeout so {@code /status/report} stays fast even when several subsystems are
 * down. It never throws — connection/timeout failures map to {@code false}.</p>
 */
@Slf4j
@Component
public class HttpHealthProbe implements HealthProbe {

    private final HttpClient httpClient;
    private final Duration timeout;
    private final String healthPath;

    public HttpHealthProbe(
            @Value("${jarvis.status.probe-timeout-ms:1500}") long timeoutMs,
            @Value("${jarvis.status.health-path:/actuator/health}") String healthPath) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.healthPath = healthPath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public boolean isHealthy(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = baseUrl.replaceAll("/+$", "") + healthPath;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300;
        } catch (Exception ex) {
            log.debug("Health probe failed for {}: {}", url, ex.toString());
            return false;
        }
    }
}
