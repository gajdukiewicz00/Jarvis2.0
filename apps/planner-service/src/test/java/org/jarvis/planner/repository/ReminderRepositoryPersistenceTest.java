package org.jarvis.planner.repository;

import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.model.ReminderStatus;
import org.jarvis.planner.model.ReminderType;
import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReminderRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    @Autowired
    private ReminderRepository reminderRepository;

    @Test
    void findByUserIdAndStatusReturnsOnlyMatchingUserAndStatus() {
        reminderRepository.save(newReminder("user-1", "active", Instant.parse("2026-03-20T09:00:00Z"),
                ReminderStatus.ACTIVE, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-1", "triggered", Instant.parse("2026-03-20T10:00:00Z"),
                ReminderStatus.TRIGGERED, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-2", "other-user", Instant.parse("2026-03-20T11:00:00Z"),
                ReminderStatus.ACTIVE, ReminderType.DAILY));

        List<String> messages = reminderRepository.findByUserIdAndStatus("user-1", ReminderStatus.ACTIVE).stream()
                .map(Reminder::getMessage)
                .toList();

        assertThat(messages).containsExactly("active");
    }

    @Test
    void findDueRemindersIncludesBoundaryAndCurrentlyAggregatesAcrossUsers() {
        Instant boundary = Instant.parse("2026-03-20T10:00:00Z");

        reminderRepository.save(newReminder("user-1", "past-due", boundary.minusSeconds(60),
                ReminderStatus.ACTIVE, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-2", "boundary-due", boundary,
                ReminderStatus.ACTIVE, ReminderType.DAILY));
        reminderRepository.save(newReminder("user-1", "future", boundary.plusSeconds(60),
                ReminderStatus.ACTIVE, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-1", "triggered", boundary.minusSeconds(120),
                ReminderStatus.TRIGGERED, ReminderType.ONCE));

        List<String> messages = reminderRepository.findDueReminders(boundary).stream()
                .map(Reminder::getMessage)
                .toList();

        assertThat(messages).containsExactlyInAnyOrder("past-due", "boundary-due");
    }

    @Test
    void findUpcomingRemindersIsUserScopedActiveOnlyInclusiveAndOrderedAscending() {
        Instant start = Instant.parse("2026-03-20T09:00:00Z");
        Instant end = Instant.parse("2026-03-20T12:00:00Z");

        reminderRepository.save(newReminder("user-1", "start-boundary", start,
                ReminderStatus.ACTIVE, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-1", "middle", Instant.parse("2026-03-20T10:30:00Z"),
                ReminderStatus.ACTIVE, ReminderType.WEEKLY));
        reminderRepository.save(newReminder("user-1", "end-boundary", end,
                ReminderStatus.ACTIVE, ReminderType.DAILY));
        reminderRepository.save(newReminder("user-1", "cancelled", Instant.parse("2026-03-20T11:00:00Z"),
                ReminderStatus.CANCELLED, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-1", "before-window", start.minusSeconds(1),
                ReminderStatus.ACTIVE, ReminderType.ONCE));
        reminderRepository.save(newReminder("user-2", "other-user", Instant.parse("2026-03-20T10:00:00Z"),
                ReminderStatus.ACTIVE, ReminderType.ONCE));

        List<String> messages = reminderRepository.findUpcomingReminders("user-1", start, end).stream()
                .map(Reminder::getMessage)
                .toList();

        assertThat(messages).containsExactly("start-boundary", "middle", "end-boundary");
    }

    private Reminder newReminder(String userId, String message, Instant reminderTime, ReminderStatus status,
            ReminderType reminderType) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setMessage(message);
        reminder.setReminderTime(reminderTime);
        reminder.setStatus(status);
        reminder.setReminderType(reminderType);
        return reminder;
    }
}
