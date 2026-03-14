package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class CalendarEventRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Test
    void findConflictsReturnsOnlyOverlappingEventsForSameUser() {
        persistEvent("user-1", "focus", LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 11, 0));
        persistEvent("user-1", "touching-boundary", LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 12, 0));
        persistEvent("user-1", "nested", LocalDateTime.of(2026, 3, 10, 10, 20),
                LocalDateTime.of(2026, 3, 10, 10, 40));
        persistEvent("user-2", "other-user", LocalDateTime.of(2026, 3, 10, 10, 15),
                LocalDateTime.of(2026, 3, 10, 10, 45));
        entityManager.flush();

        List<CalendarEvent> conflicts = calendarEventRepository.findConflicts(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 10, 15),
                LocalDateTime.of(2026, 3, 10, 10, 35),
                null);
        List<String> titles = conflicts.stream().map(CalendarEvent::getTitle).toList();

        assertEquals(2, conflicts.size());
        assertTrue(titles.contains("focus"));
        assertTrue(titles.contains("nested"));
    }

    @Test
    void findConflictsExcludesSpecifiedEventIdAndTreatsTouchingBoundaryAsNonConflict() {
        CalendarEvent boundary = persistEvent("user-1", "boundary", LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 11, 0));
        CalendarEvent moved = persistEvent("user-1", "moved", LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 12, 0));
        entityManager.flush();

        List<CalendarEvent> conflicts = calendarEventRepository.findConflicts(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 12, 0),
                moved.getId());

        assertEquals(0, conflicts.size());
        assertEquals("boundary", boundary.getTitle());
    }

    @Test
    void findConflictsTreatsNullEndTimeAsOpenEndedForQueryOverlap() {
        persistEvent("user-1", "open-ended", LocalDateTime.of(2026, 3, 10, 9, 0), null);
        entityManager.flush();

        List<CalendarEvent> conflicts = calendarEventRepository.findConflicts(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 9, 15),
                LocalDateTime.of(2026, 3, 10, 9, 45),
                null);

        assertEquals(1, conflicts.size());
        assertEquals("open-ended", conflicts.get(0).getTitle());
    }

    private CalendarEvent persistEvent(String userId, String title, LocalDateTime startTime, LocalDateTime endTime) {
        CalendarEvent event = new CalendarEvent();
        event.setUserId(userId);
        event.setTitle(title);
        event.setStartTime(startTime);
        event.setEndTime(endTime);
        entityManager.persist(event);
        return event;
    }
}
