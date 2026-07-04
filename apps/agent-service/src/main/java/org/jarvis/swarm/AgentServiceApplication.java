package org.jarvis.swarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Role-based agent swarm service (EPIC 12 — "House Party Protocol").
 *
 * <p>Runs multiple bounded agents with explicit roles, per-task permissions, sandbox
 * isolation, a lifecycle (queue/pause/resume/cancel), and combined reports. It reuses
 * the shared safety substrate ({@code ToolPermission}, {@code ToolPermissionPolicy},
 * {@code SystemPanicState}) so every guarded action is checked against panic, dryRun,
 * role+user grants, and the system permission backstop before any side effect.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
