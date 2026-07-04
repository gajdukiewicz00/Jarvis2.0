package org.jarvis.planner.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.planner.model.DailyPlan;
import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class DailyPlanRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private DailyPlanRepository dailyPlanRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByUserIdAndPlanDateAndExistsAreUserScoped() {
        LocalDate planDate = LocalDate.of(2026, 3, 25);
        insertDailyPlan("user-1", planDate, "{\"tasks\":1}", false, Instant.parse("2026-03-25T06:00:00Z"));
        insertDailyPlan("user-2", planDate, "{\"tasks\":2}", true, Instant.parse("2026-03-25T07:00:00Z"));

        DailyPlan loaded = dailyPlanRepository.findByUserIdAndPlanDate("user-1", planDate).orElseThrow();

        assertThat(loaded.getUserId()).isEqualTo("user-1");
        assertThat(dailyPlanRepository.existsByUserIdAndPlanDate("user-1", planDate)).isTrue();
        assertThat(dailyPlanRepository.existsByUserIdAndPlanDate("user-1", planDate.plusDays(1))).isFalse();
        assertThat(dailyPlanRepository.findByUserIdAndPlanDate("user-1", planDate.plusDays(1))).isEmpty();
    }

    @Test
    void savingDuplicateUserAndPlanDateViolatesUniqueConstraint() {
        LocalDate planDate = LocalDate.of(2026, 3, 26);
        insertDailyPlan("user-1", planDate, "{\"tasks\":1}", false, Instant.parse("2026-03-26T06:00:00Z"));

        assertThatThrownBy(() -> insertDailyPlan(
                "user-1",
                planDate,
                "{\"tasks\":2}",
                true,
                Instant.parse("2026-03-26T07:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void planJsonRoundTripsThroughJsonbNormalizationWhenSeededViaSql() throws Exception {
        String initialJson = "{ \"meta\": {\"b\":2,\"a\":1}, \"tasks\": [] }";
        Long id = insertDailyPlan("user-1", LocalDate.of(2026, 3, 27), initialJson, false,
                Instant.parse("2026-03-27T06:00:00Z"));

        String rawJson = jdbcTemplate.queryForObject(
                "select plan_json::text from planner.daily_plans where id = ?",
                String.class,
                id);
        DailyPlan loaded = dailyPlanRepository.findById(id).orElseThrow();

        assertThat(OBJECT_MAPPER.readTree(rawJson)).isEqualTo(OBJECT_MAPPER.readTree(initialJson));
        assertThat(loaded.getPlanJson()).isEqualTo(rawJson);
        assertThat(loaded.getGeneratedAt()).isNotNull();
        assertThat(loaded.getConfirmed()).isFalse();
    }

    @Test
    void repositorySaveAndFlushPersistsJsonbPlanColumn() throws Exception {
        DailyPlan saved = dailyPlanRepository.saveAndFlush(
                newDailyPlan("user-1", LocalDate.of(2026, 3, 28), "{\"tasks\":[]}", false));
        entityManager.clear();

        String rawJson = jdbcTemplate.queryForObject(
                "select plan_json::text from planner.daily_plans where id = ?",
                String.class,
                saved.getId());
        DailyPlan reloaded = dailyPlanRepository.findById(saved.getId()).orElseThrow();

        assertThat(OBJECT_MAPPER.readTree(rawJson)).isEqualTo(OBJECT_MAPPER.readTree("{\"tasks\":[]}"));
        assertThat(reloaded.getPlanJson()).isEqualTo(rawJson);
        assertThat(reloaded.getConfirmed()).isFalse();
    }

    @Test
    void invalidJsonbPayloadIsRejectedWhenSeededViaSql() {
        assertThatThrownBy(() -> insertDailyPlan(
                "user-1",
                LocalDate.of(2026, 3, 29),
                "not-json",
                false,
                Instant.parse("2026-03-29T06:00:00Z")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private DailyPlan newDailyPlan(String userId, LocalDate planDate, String planJson, boolean confirmed) {
        DailyPlan dailyPlan = new DailyPlan();
        dailyPlan.setUserId(userId);
        dailyPlan.setPlanDate(planDate);
        dailyPlan.setPlanJson(planJson);
        dailyPlan.setConfirmed(confirmed);
        return dailyPlan;
    }

    private Long insertDailyPlan(String userId, LocalDate planDate, String planJson, boolean confirmed,
            Instant generatedAt) {
        return jdbcTemplate.queryForObject(
                """
                insert into planner.daily_plans (user_id, plan_date, plan_json, generated_at, confirmed)
                values (?, ?, cast(? as jsonb), ?, ?)
                returning id
                """,
                Long.class,
                userId,
                planDate,
                planJson,
                Timestamp.from(generatedAt),
                confirmed);
    }
}
