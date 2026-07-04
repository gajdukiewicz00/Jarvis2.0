package org.jarvis.userprofile.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserGoalTest {

    @Test
    void gettersAndSettersRoundTripAllFields() {
        UserGoal goal = new UserGoal();
        goal.setId(1L);
        goal.setUserId("user-1");
        goal.setTitle("Ship feature");
        goal.setDescription("Details");
        goal.setCategory("Work");
        goal.setTargetValue(BigDecimal.TEN);
        goal.setCurrentValue(BigDecimal.ONE);
        goal.setTargetDate(LocalDate.of(2026, 6, 1));
        goal.setStatus("active");
        LocalDateTime deadline = LocalDateTime.of(2026, 6, 1, 9, 0);
        goal.setDeadline(deadline);
        LocalDateTime now = LocalDateTime.now();
        goal.setCreatedAt(now);
        goal.setUpdatedAt(now);

        assertEquals(1L, goal.getId());
        assertEquals("user-1", goal.getUserId());
        assertEquals("Ship feature", goal.getTitle());
        assertEquals("Details", goal.getDescription());
        assertEquals("Work", goal.getCategory());
        assertEquals(BigDecimal.TEN, goal.getTargetValue());
        assertEquals(BigDecimal.ONE, goal.getCurrentValue());
        assertEquals(LocalDate.of(2026, 6, 1), goal.getTargetDate());
        assertEquals("active", goal.getStatus());
        assertEquals(deadline, goal.getDeadline());
        assertEquals(now, goal.getCreatedAt());
        assertEquals(now, goal.getUpdatedAt());
    }

    @Test
    void allArgsConstructorProducesEqualAndConsistentInstances() {
        LocalDateTime now = LocalDateTime.now();
        UserGoal a = new UserGoal(1L, "user-1", "Title", "Desc", "Cat",
                BigDecimal.ONE, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), "active", now, now, now);
        UserGoal b = new UserGoal(1L, "user-1", "Title", "Desc", "Cat",
                BigDecimal.ONE, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), "active", now, now, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new UserGoal());
        assertNotNull(a.toString());
    }

    @Test
    void onCreateSetsDefaultsWhenMissing() {
        UserGoal goal = new UserGoal();

        goal.onCreate();

        assertEquals("active", goal.getStatus());
        assertEquals(BigDecimal.ZERO, goal.getCurrentValue());
        assertNotNull(goal.getCreatedAt());
        assertNotNull(goal.getUpdatedAt());
    }

    @Test
    void onCreateKeepsExplicitlySetStatusAndCurrentValue() {
        UserGoal goal = new UserGoal();
        goal.setStatus("completed");
        goal.setCurrentValue(BigDecimal.TEN);

        goal.onCreate();

        assertEquals("completed", goal.getStatus());
        assertEquals(BigDecimal.TEN, goal.getCurrentValue());
    }

    @Test
    void onUpdateRefreshesUpdatedAtTimestamp() {
        UserGoal goal = new UserGoal();
        goal.onCreate();
        LocalDateTime createdUpdatedAt = goal.getUpdatedAt();

        goal.onUpdate();

        assertNotNull(goal.getUpdatedAt());
        assertTrue(!goal.getUpdatedAt().isBefore(createdUpdatedAt));
    }
}
