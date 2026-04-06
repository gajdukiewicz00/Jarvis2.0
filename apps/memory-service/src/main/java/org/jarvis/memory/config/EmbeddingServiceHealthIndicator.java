package org.jarvis.memory.config;

import org.jarvis.memory.service.EmbeddingClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("embeddingService")
public class EmbeddingServiceHealthIndicator implements HealthIndicator {

    private final EmbeddingClient embeddingClient;

    public EmbeddingServiceHealthIndicator(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @Override
    public Health health() {
        EmbeddingClient.EmbeddingServiceHealth health = embeddingClient.getHealth();
        Health.Builder builder = health.healthy() ? Health.up() : Health.down();
        builder.withDetail("status", health.status());
        if (health.modelName() != null) {
            builder.withDetail("model", health.modelName());
        }
        if (health.dimension() != null) {
            builder.withDetail("dimension", health.dimension());
        }
        if (health.error() != null) {
            builder.withDetail("error", health.error());
        }
        return builder.build();
    }
}
