package org.jarvis.llm.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 3 — refuses to start the LLM service if any configured LLM URL points
 * outside the local cluster / loopback. Hard guard against accidental cloud
 * AI usage in production.
 *
 * <p>Allow list (case-insensitive):</p>
 * <ul>
 *   <li>{@code host-model-daemon.jarvis-prod.svc.cluster.local} (and any
 *       suffix matching {@code .svc.cluster.local})</li>
 *   <li>{@code host-model-daemon}, {@code localhost}, {@code 127.0.0.1}</li>
 *   <li>RFC1918 private addresses (10/8, 172.16/12, 192.168/16)</li>
 *   <li>RFC1122 link-local 169.254/16 (used by some test setups)</li>
 * </ul>
 *
 * <p>The check can be disabled per environment via
 * {@code jarvis.llm.local-only: false} — but production profiles MUST keep it
 * on.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class LocalOnlyEnforcer {

    private static final List<String> ALLOWED_HOSTNAMES = List.of(
            "host-model-daemon",
            "host-model-daemon.jarvis-prod.svc.cluster.local",
            "embedding-service",
            "memory-service",
            "localhost"
    );

    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*"
    );

    private static final Pattern CLOUD_HINT = Pattern.compile(
            "(api\\.openai\\.com|api\\.anthropic\\.com|generativelanguage\\.googleapis\\.com|"
            + "api\\.cohere\\.ai|api\\.mistral\\.ai|api\\.perplexity\\.ai|api\\.deepseek\\.com|"
            + "api\\.together\\.xyz|api\\.fireworks\\.ai|api\\.replicate\\.com|api\\.groq\\.com|"
            + "api\\.x\\.ai|api\\.huggingface\\.co|huggingfacehub|huggingface\\.co)",
            Pattern.CASE_INSENSITIVE
    );

    @Value("${jarvis.llm.local-only:true}")
    private boolean localOnly;

    @Value("${llm.base-url:}")
    private String llmBaseUrl;

    @Value("${memory.service.url:}")
    private String memoryServiceUrl;

    @Value("${services.user-profile.base-url:}")
    private String userProfileUrl;

    @Value("${jarvis.llm.host-daemon.host:host-model-daemon.jarvis-prod.svc.cluster.local}")
    private String hostDaemonHost;

    @PostConstruct
    void enforce() {
        if (!localOnly) {
            log.warn("⚠ jarvis.llm.local-only=false — LOCAL-ONLY enforcement DISABLED. "
                    + "This is acceptable only for tests, NEVER for production.");
            return;
        }

        check("llm.base-url", llmBaseUrl);
        check("memory.service.url", memoryServiceUrl);
        check("services.user-profile.base-url", userProfileUrl);
        check("jarvis.llm.host-daemon.host", "http://" + hostDaemonHost);

        log.info("✅ local-only enforcement passed for llm-service "
                + "(daemon host = {})", hostDaemonHost);
    }

    private void check(String prop, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        if (CLOUD_HINT.matcher(value).find()) {
            throw new IllegalStateException(
                    "REFUSING to start: " + prop + "='" + value
                            + "' looks like a cloud LLM provider. "
                            + "SPEC-1 forbids cloud AI in production. Set jarvis.llm.local-only=false "
                            + "only if you really know what you are doing.");
        }

        String hostname = extractHost(value);
        if (hostname == null) {
            return; // not a URL, ignore
        }

        String lower = hostname.toLowerCase();
        if (lower.endsWith(".svc.cluster.local")) {
            return;
        }
        if (ALLOWED_HOSTNAMES.contains(lower)) {
            return;
        }
        if (PRIVATE_IP.matcher(lower).matches()) {
            return;
        }
        // Bare service name like "rabbitmq" / "kafka" / "host-model-daemon"
        if (!lower.contains(".")) {
            return;
        }

        throw new IllegalStateException(
                "REFUSING to start: " + prop + "='" + value
                        + "' resolves to a non-local host '" + lower + "'. "
                        + "Allowed: *.svc.cluster.local, localhost, 127.x, 10.x, 172.16-31.x, 192.168.x. "
                        + "Disable this check (only for tests) with jarvis.llm.local-only=false.");
    }

    private String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            return host;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
