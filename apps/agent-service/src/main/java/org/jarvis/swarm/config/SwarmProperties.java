package org.jarvis.swarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed, immutable configuration for the agent swarm (prefix {@code swarm}).
 */
@ConfigurationProperties(prefix = "swarm")
public record SwarmProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Workspace workspace,
        @DefaultValue Queue queue,
        @DefaultValue Task task,
        @DefaultValue SwarmRun swarmRun) {

    public record Workspace(@DefaultValue("/tmp/jarvis-agents") String dir) {}

    public record Queue(
            @DefaultValue("64") int capacity,
            @DefaultValue("3") int workerPoolSize) {}

    public record Task(
            @DefaultValue("120") int defaultTimeoutSeconds,
            @DefaultValue("1") int defaultMaxRetries) {}

    public record SwarmRun(
            @DefaultValue("60") int waitTimeoutSeconds,
            @DefaultValue("7") int maxRoles) {}
}
