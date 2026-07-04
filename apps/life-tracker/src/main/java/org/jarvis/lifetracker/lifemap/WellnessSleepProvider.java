package org.jarvis.lifetracker.lifemap;

import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Real sleep source for the life-map — reads the latest SLEEP wellness entry
 * for the day (synced from the phone's Health Connect via sync-service).
 * Overrides the empty Phase-11 stub.
 */
@Component
@RequiredArgsConstructor
public class WellnessSleepProvider implements DailySummaryService.SleepProvider {

    private final WellnessLogRepository repository;

    @Override
    public Double lastNightHours(String userId, LocalDate day) {
        List<WellnessLog> logs = repository.findByUserIdAndDayOrderByLoggedAtAsc(userId, day);
        Double latest = null;
        for (WellnessLog log : logs) {
            if (log.getType() == WellnessType.SLEEP && log.getNumericValue() != null) {
                latest = log.getNumericValue();
            }
        }
        return latest;
    }
}
