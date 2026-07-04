package org.jarvis.userprofile.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserHabitTest {

    @Test
    void gettersAndSettersRoundTripAllFields() {
        UserHabit habit = new UserHabit();
        habit.setId(1L);
        habit.setUserId("user-1");
        habit.setName("Morning review");
        habit.setDescription("Review the plan");
        habit.setFrequency("DAILY");
        habit.setTimeOfDay("morning");
        habit.setReminderEnabled(true);
        habit.setStreakDays(3);
        habit.setLastCompletedDate(LocalDate.of(2026, 6, 1));
        LocalDateTime now = LocalDateTime.now();
        habit.setCreatedAt(now);
        habit.setUpdatedAt(now);

        assertEquals(1L, habit.getId());
        assertEquals("user-1", habit.getUserId());
        assertEquals("Morning review", habit.getName());
        assertEquals("Review the plan", habit.getDescription());
        assertEquals("DAILY", habit.getFrequency());
        assertEquals("morning", habit.getTimeOfDay());
        assertTrue(habit.getReminderEnabled());
        assertEquals(3, habit.getStreakDays());
        assertEquals(LocalDate.of(2026, 6, 1), habit.getLastCompletedDate());
        assertEquals(now, habit.getCreatedAt());
        assertEquals(now, habit.getUpdatedAt());
    }

    @Test
    void allArgsConstructorProducesEqualAndConsistentInstances() {
        LocalDateTime now = LocalDateTime.now();
        UserHabit a = new UserHabit(1L, "user-1", "Name", "Desc", "DAILY", "morning",
                true, 2, LocalDate.of(2026, 1, 1), now, now);
        UserHabit b = new UserHabit(1L, "user-1", "Name", "Desc", "DAILY", "morning",
                true, 2, LocalDate.of(2026, 1, 1), now, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new UserHabit());
        assertNotNull(a.toString());
    }

    @Test
    void onCreateSetsDefaultsWhenMissing() {
        UserHabit habit = new UserHabit();

        habit.onCreate();

        assertFalse(habit.getReminderEnabled());
        assertEquals(0, habit.getStreakDays());
        assertNotNull(habit.getCreatedAt());
        assertNotNull(habit.getUpdatedAt());
    }

    @Test
    void onCreateKeepsExplicitlySetReminderAndStreak() {
        UserHabit habit = new UserHabit();
        habit.setReminderEnabled(true);
        habit.setStreakDays(7);

        habit.onCreate();

        assertTrue(habit.getReminderEnabled());
        assertEquals(7, habit.getStreakDays());
    }

    @Test
    void onUpdateRefreshesUpdatedAtTimestamp() {
        UserHabit habit = new UserHabit();
        habit.onCreate();

        habit.onUpdate();

        assertNotNull(habit.getUpdatedAt());
    }
}
