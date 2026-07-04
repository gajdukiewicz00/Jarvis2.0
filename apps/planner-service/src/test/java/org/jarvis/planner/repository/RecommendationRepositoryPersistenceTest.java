package org.jarvis.planner.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.planner.model.Recommendation;
import org.jarvis.planner.model.RecommendationStatus;
import org.jarvis.planner.model.RecommendationType;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class RecommendationRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByUserIdAndStatusOrderByCreatedAtDescIsUserScopedAndDescending() {
        insertRecommendation("user-1", "pending-old", RecommendationStatus.PENDING, "{\"kind\":\"old\"}",
                Instant.parse("2026-04-01T10:00:00Z"), Instant.parse("2026-04-01T11:00:00Z"), null);
        insertRecommendation("user-1", "accepted", RecommendationStatus.ACCEPTED, "{\"kind\":\"accepted\"}",
                Instant.parse("2026-04-01T10:01:00Z"), Instant.parse("2026-04-01T12:00:00Z"),
                Instant.parse("2026-04-01T09:00:00Z"));
        insertRecommendation("user-1", "pending-new", RecommendationStatus.PENDING, "{\"kind\":\"new\"}",
                Instant.parse("2026-04-01T10:02:00Z"), Instant.parse("2026-04-01T13:00:00Z"), null);
        insertRecommendation("user-2", "other-user", RecommendationStatus.PENDING, "{\"kind\":\"foreign\"}",
                Instant.parse("2026-04-01T10:03:00Z"), Instant.parse("2026-04-01T14:00:00Z"), null);

        List<String> titles = recommendationRepository
                .findByUserIdAndStatusOrderByCreatedAtDesc("user-1", RecommendationStatus.PENDING)
                .stream()
                .map(Recommendation::getTitle)
                .toList();

        assertThat(titles).containsExactly("pending-new", "pending-old");
    }

    @Test
    void findByUserIdOrderByCreatedAtDescReturnsAllStatusesForUserInDescendingOrder() {
        insertRecommendation("user-1", "oldest", RecommendationStatus.PENDING, "{\"rank\":1}",
                Instant.parse("2026-04-02T10:00:00Z"), Instant.parse("2026-04-02T11:00:00Z"), null);
        insertRecommendation("user-1", "middle", RecommendationStatus.ACCEPTED, "{\"rank\":2}",
                Instant.parse("2026-04-02T10:01:00Z"), Instant.parse("2026-04-02T12:00:00Z"),
                Instant.parse("2026-04-02T08:00:00Z"));
        insertRecommendation("user-1", "newest", RecommendationStatus.EXPIRED, "{\"rank\":3}",
                Instant.parse("2026-04-02T10:02:00Z"), Instant.parse("2026-04-02T13:00:00Z"), null);
        insertRecommendation("user-2", "other-user", RecommendationStatus.PENDING, "{\"rank\":4}",
                Instant.parse("2026-04-02T10:03:00Z"), Instant.parse("2026-04-02T14:00:00Z"), null);

        List<String> titles = recommendationRepository.findByUserIdOrderByCreatedAtDesc("user-1").stream()
                .map(Recommendation::getTitle)
                .toList();

        assertThat(titles).containsExactly("newest", "middle", "oldest");
    }

    @Test
    void dataJsonbRoundTripsAndDateFieldsPersistWhenSeededViaSql() throws Exception {
        String inputJson = "{ \"extra\": {\"z\":3,\"a\":1}, \"score\": 0.85 }";
        Long id = insertRecommendation(
                "user-1",
                "jsonb-roundtrip",
                RecommendationStatus.PENDING,
                inputJson,
                Instant.parse("2026-04-03T10:00:00Z"),
                Instant.parse("2026-04-03T11:00:00Z"),
                Instant.parse("2026-04-03T09:00:00Z"));

        String rawJson = jdbcTemplate.queryForObject(
                "select data::text from planner.recommendations where id = ?",
                String.class,
                id);
        Recommendation loaded = recommendationRepository.findById(id).orElseThrow();

        assertThat(OBJECT_MAPPER.readTree(rawJson)).isEqualTo(OBJECT_MAPPER.readTree(inputJson));
        assertThat(loaded.getData()).isEqualTo(rawJson);
        assertThat(loaded.getExpiresAt()).isEqualTo(Instant.parse("2026-04-03T11:00:00Z"));
        assertThat(loaded.getRespondedAt()).isEqualTo(Instant.parse("2026-04-03T09:00:00Z"));
    }

    @Test
    void repositorySaveAndFlushPersistsJsonbDataColumn() throws Exception {
        Recommendation saved = recommendationRepository.saveAndFlush(newRecommendation(
                "user-1",
                "jsonb-write",
                RecommendationStatus.PENDING,
                "{\"score\":1}",
                null,
                null));
        entityManager.clear();

        String rawJson = jdbcTemplate.queryForObject(
                "select data::text from planner.recommendations where id = ?",
                String.class,
                saved.getId());
        Recommendation reloaded = recommendationRepository.findById(saved.getId()).orElseThrow();

        assertThat(OBJECT_MAPPER.readTree(rawJson)).isEqualTo(OBJECT_MAPPER.readTree("{\"score\":1}"));
        assertThat(reloaded.getData()).isEqualTo(rawJson);
        assertThat(reloaded.getStatus()).isEqualTo(RecommendationStatus.PENDING);
    }

    private Recommendation newRecommendation(String userId, String title, RecommendationStatus status, String data,
            Instant expiresAt, Instant respondedAt) {
        Recommendation recommendation = new Recommendation();
        recommendation.setUserId(userId);
        recommendation.setRecommendationType(RecommendationType.PRODUCTIVITY);
        recommendation.setTitle(title);
        recommendation.setMessage("Review your planner habits");
        recommendation.setPriority(TaskPriority.MEDIUM);
        recommendation.setStatus(status);
        recommendation.setData(data);
        recommendation.setExpiresAt(expiresAt);
        recommendation.setRespondedAt(respondedAt);
        return recommendation;
    }

    private Long insertRecommendation(String userId, String title, RecommendationStatus status, String data,
            Instant createdAt, Instant expiresAt, Instant respondedAt) {
        return jdbcTemplate.queryForObject(
                """
                insert into planner.recommendations
                    (user_id, recommendation_type, title, message, priority, status, data, created_at, expires_at, responded_at)
                values
                    (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as timestamp), cast(? as timestamp), cast(? as timestamp))
                returning id
                """,
                Long.class,
                userId,
                RecommendationType.PRODUCTIVITY.name(),
                title,
                "Review your planner habits",
                TaskPriority.MEDIUM.name(),
                status.name(),
                data,
                toSqlTimestamp(createdAt),
                toSqlTimestamp(expiresAt),
                toSqlTimestamp(respondedAt));
    }

    private String toSqlTimestamp(Instant value) {
        return value == null ? null : SQL_TIMESTAMP.format(value);
    }
}
