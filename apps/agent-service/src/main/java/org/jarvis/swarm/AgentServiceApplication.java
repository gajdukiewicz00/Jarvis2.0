package org.jarvis.swarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Role-based agent swarm service (EPIC 12 — "House Party Protocol").
 *
 * <p>Runs multiple bounded agents with explicit roles, per-task permissions, sandbox
 * isolation, a lifecycle (queue/pause/resume/cancel), and combined reports. It reuses
 * the shared safety substrate ({@code ToolPermission}, {@code ToolPermissionPolicy},
 * {@code SystemPanicState}) so every guarded action is checked against panic, dryRun,
 * role+user grants, and the system permission backstop before any side effect.</p>
 *
 * <p>DataSource/JPA/Flyway/Transaction auto-configuration is excluded UNCONDITIONALLY
 * here: the default {@code AgentTaskStore} is in-memory (or file-backed), and neither
 * needs a database. {@code org.jarvis.swarm.task.jpa.PostgresTaskStoreAutoConfiguration}
 * re-imports exactly these four, but only when {@code jarvis.agent.task-store=postgres}
 * is explicitly set — so by default this service never attempts a DB connection.</p>
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        TransactionAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class AgentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
