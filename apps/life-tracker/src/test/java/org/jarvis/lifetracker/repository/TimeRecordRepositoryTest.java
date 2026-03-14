package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.TimeRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class TimeRecordRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TimeRecordRepository timeRecordRepository;

    @Test
    void findByUserIdOrderByStartTimeDescReturnsNewestStartedRecordFirst() {
        persistRecord("user-1", "deep-work", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 10, 0));
        persistRecord("user-1", "meeting", LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 12, 0));
        persistRecord("user-2", "other-user", LocalDateTime.of(2026, 3, 10, 13, 0),
                LocalDateTime.of(2026, 3, 10, 14, 0));
        entityManager.flush();

        List<TimeRecord> result = timeRecordRepository.findByUserIdOrderByStartTimeDesc("user-1");

        assertEquals(2, result.size());
        assertEquals("meeting", result.get(0).getActivity());
        assertEquals("deep-work", result.get(1).getActivity());
    }

    @Test
    void findTopByUserIdOrderByEndTimeDescReturnsLatestFinishedRecord() {
        persistRecord("user-1", "first", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 10, 0));
        persistRecord("user-1", "latest", LocalDateTime.of(2026, 3, 10, 10, 30),
                LocalDateTime.of(2026, 3, 10, 12, 30));
        entityManager.flush();

        Optional<TimeRecord> latest = timeRecordRepository.findTopByUserIdOrderByEndTimeDesc("user-1");

        assertTrue(latest.isPresent());
        assertEquals("latest", latest.get().getActivity());
        assertEquals(LocalDateTime.of(2026, 3, 10, 12, 30), latest.get().getEndTime());
    }

    private void persistRecord(String userId, String activity, LocalDateTime startTime, LocalDateTime endTime) {
        TimeRecord record = new TimeRecord();
        record.setUserId(userId);
        record.setActivity(activity);
        record.setCategory("work");
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setDurationSeconds(java.time.Duration.between(startTime, endTime).toSeconds());
        entityManager.persist(record);
    }
}
