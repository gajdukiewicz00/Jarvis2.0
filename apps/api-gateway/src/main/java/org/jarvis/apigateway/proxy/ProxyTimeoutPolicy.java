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
            case "security-service", "life-tracker", "analytics-service", "vision-security-service", "planner-service" ->
                    EXTENDED_READ_TIMEOUT;
            case "llm-service" -> LLM_READ_TIMEOUT;
            default -> DEFAULT_READ_TIMEOUT;
        };
    }
}
