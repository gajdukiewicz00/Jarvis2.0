package org.jarvis.memory.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("pgvector")
public class PgvectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public PgvectorHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Boolean extensionPresent = jdbcTemplate.queryForObject(
                    "select exists (select 1 from pg_extension where extname = 'vector')",
                    Boolean.class);
            if (Boolean.TRUE.equals(extensionPresent)) {
                return Health.up().withDetail("extension", "vector").build();
            }
            return Health.down()
                    .withDetail("extension", "vector")
                    .withDetail("error", "pgvector extension is not installed")
                    .build();
        } catch (RuntimeException ex) {
            return Health.down()
                    .withDetail("extension", "vector")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
