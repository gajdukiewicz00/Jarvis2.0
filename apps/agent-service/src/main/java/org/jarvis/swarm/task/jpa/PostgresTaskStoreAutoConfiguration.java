package org.jarvis.swarm.task.jpa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Opt-in bootstrap for the Postgres-backed {@link JpaAgentTaskStore}.
 *
 * <p>{@code AgentServiceApplication} excludes {@link DataSourceAutoConfiguration},
 * {@link HibernateJpaAutoConfiguration}, {@link FlywayAutoConfiguration}, and
 * {@link TransactionAutoConfiguration} UNCONDITIONALLY, so the default deployment (the
 * in-memory task store) never attempts a database connection, never runs Flyway, and
 * needs no {@code spring.datasource.*} configuration at all.
 *
 * <p>This class re-imports exactly those four auto-configurations, but ONLY when
 * {@code jarvis.agent.task-store=postgres} is set — at which point a real
 * {@code spring.datasource.url} (see {@code application.yml}) becomes required, Flyway
 * runs {@code db/migration/V1__create_agent_task.sql}, and {@link JpaAgentTaskStore}
 * (itself gated by the same property) becomes the active {@code AgentTaskStore} bean.</p>
 */
@Configuration
@ConditionalOnProperty(name = "jarvis.agent.task-store", havingValue = "postgres")
@Import({
        DataSourceAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = "org.jarvis.swarm.task.jpa")
@EntityScan(basePackages = "org.jarvis.swarm.task.jpa")
public class PostgresTaskStoreAutoConfiguration {
}
