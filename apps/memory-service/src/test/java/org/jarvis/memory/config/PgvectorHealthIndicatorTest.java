package org.jarvis.memory.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgvectorHealthIndicatorTest {

    private static final String QUERY =
            "select exists (select 1 from pg_extension where extname = 'vector')";

    private JdbcTemplate jdbcTemplate;
    private PgvectorHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        indicator = new PgvectorHealthIndicator(jdbcTemplate);
    }

    @Test
    void healthIsUpWhenExtensionPresent() {
        when(jdbcTemplate.queryForObject(QUERY, Boolean.class)).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("extension", "vector");
    }

    @Test
    void healthIsDownWhenExtensionMissing() {
        when(jdbcTemplate.queryForObject(QUERY, Boolean.class)).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "pgvector extension is not installed");
    }

    @Test
    void healthIsDownWhenQueryThrows() {
        when(jdbcTemplate.queryForObject(QUERY, Boolean.class))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "connection refused");
    }
}
