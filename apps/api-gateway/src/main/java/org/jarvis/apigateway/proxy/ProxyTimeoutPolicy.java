package org.jarvis.apigateway.proxy;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ProxyTimeoutPolicy {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXTENDED_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LLM_READ_TIMEOUT = Duration.ofSeconds(60);

    public Duration connectTimeout(String downstreamService) {
        return DEFAULT_CONNECT_TIMEOUT;
    }

    public Duration readTimeout(String downstreamService) {
        return switch (downstreamService) {
            case "security-service", "life-tracker", "analytics-service", "vision-security-service", "planner-service", "memory-service" ->
                    EXTENDED_READ_TIMEOUT;
            // agent-service's POST /api/v1/agents/swarm?awaitCompletion=true blocks on
            // SwarmCoordinator#awaitAndReport, which can take up to swarm.swarm-run.wait-timeout-seconds
            // (default 60s) — the default 10s read timeout would spuriously time out that call.
            case "llm-service", "agent-service" -> LLM_READ_TIMEOUT;
            default -> DEFAULT_READ_TIMEOUT;
        };
    }
}
