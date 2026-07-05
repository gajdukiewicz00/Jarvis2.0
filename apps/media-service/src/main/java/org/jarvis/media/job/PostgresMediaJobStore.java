package org.jarvis.media.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed job store: persists every {@link MediaJob} as a JSON payload row
 * so jobs survive a pod restart AND are shared across replicas (unlike {@link
 * FileBackedMediaJobStore}, which only survives a restart of the same pod backed by
 * the same volume). Opt in with {@code jarvis.media.job-store=postgres}; the
 * in-memory store remains the default and this bean does not exist otherwise.
 *
 * <p>Deliberately plain JDBC ({@link JdbcTemplate}), not JPA: there is exactly one
 * table and one access pattern (save / find-by-id / find-by-user), so an ORM would
 * add ceremony without buying anything. Schema migrations run via Flyway against a
 * dedicated {@code db/media/migration} classpath location, invoked directly in the
 * constructor (this module intentionally excludes Spring Boot's {@code
 * DataSourceAutoConfiguration}/{@code FlywayAutoConfiguration} — see {@code
 * application.yml} — so adding the jdbc/postgres/flyway jars to the classpath has
 * zero effect on the service until this property is explicitly set).</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "jarvis.media.job-store", havingValue = "postgres")
public class PostgresMediaJobStore implements MediaJobStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresMediaJobStore(
            ObjectMapper mapper,
            @Value("${jarvis.media.job-store.jdbc-url:jdbc:postgresql://localhost:5432/media_jobs}")
            String jdbcUrl,
            @Value("${jarvis.media.job-store.jdbc-username:jarvis}") String jdbcUsername,
            @Value("${jarvis.media.job-store.jdbc-password:}") String jdbcPassword) {
        this.mapper = mapper;
        DataSource dataSource = DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(jdbcUsername)
                .password(jdbcPassword)
                .build();
        migrate(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("Postgres media job store ready ({})", jdbcUrl);
    }

    private void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/media/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @Override
    public MediaJob save(MediaJob job) {
        String payload = writeJson(job);
        Timestamp createdAt = Timestamp.from(job.createdAt());
        int updated = jdbc.update(
                "UPDATE media_jobs SET user_id = ?, created_at = ?, payload = ? WHERE id = ?",
                job.userId(), createdAt, payload, job.id());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO media_jobs (id, user_id, created_at, payload) VALUES (?, ?, ?, ?)",
                    job.id(), job.userId(), createdAt, payload);
        }
        return job;
    }

    @Override
    public Optional<MediaJob> findById(String id) {
        List<MediaJob> found = jdbc.query(
                "SELECT payload FROM media_jobs WHERE id = ?",
                (rs, rowNum) -> readJson(rs.getString("payload")),
                id);
        return found.stream().findFirst();
    }

    @Override
    public List<MediaJob> findByUser(String userId) {
        return jdbc.query(
                "SELECT payload FROM media_jobs WHERE user_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> readJson(rs.getString("payload")),
                userId);
    }

    private String writeJson(MediaJob job) {
        try {
            return mapper.writeValueAsString(job);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize media job " + job.id(), e);
        }
    }

    private MediaJob readJson(String payload) {
        try {
            return mapper.readValue(payload, MediaJob.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize media job payload", e);
        }
    }
}
