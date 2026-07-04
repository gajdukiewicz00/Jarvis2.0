package org.jarvis.lifetracker.lifemap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.LifeActivityEntry;
import org.jarvis.lifetracker.repository.LifeActivityRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12 — durable activity timeline, scoped per user, persisted in Postgres
 * ({@code life_activity_entries}). History now survives restarts.
 *
 * <p>(Kept the class name for injection compatibility; it is no longer
 * in-memory — it delegates to {@link LifeActivityRepository}.)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryActivityStore {

    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();

    private final LifeActivityRepository repository;

    public LifeMapDtos.ActivityEntry record(String userId, String appName, String windowTitle,
                                             Instant startedAt, Instant endedAt,
                                             Long providedDuration, TimeCategory category,
                                             String source) {
        Instant start = startedAt == null ? Instant.now() : startedAt;
        Instant end = endedAt == null ? start : endedAt;
        long duration = providedDuration != null ? providedDuration
                : Math.max(Duration.between(start, end).getSeconds(), 0);

        LifeActivityEntry entity = new LifeActivityEntry();
        entity.setEntryId("act-" + UUID.randomUUID());
        entity.setUserId(safe(userId));
        entity.setStartedAt(start);
        entity.setEndedAt(end);
        entity.setDurationSeconds(duration);
        entity.setCategory(category);
        entity.setAppName(appName);
        entity.setWindowTitle(windowTitle);
        entity.setSource(source == null ? "agent" : source);
        repository.save(entity);
        return toDto(entity);
    }

    public List<LifeMapDtos.ActivityEntry> entriesForDay(String userId, LocalDate day) {
        Instant from = day.atStartOfDay(LOCAL_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(LOCAL_ZONE).toInstant();
        List<LifeMapDtos.ActivityEntry> out = new ArrayList<>();
        for (LifeActivityEntry e : repository
                .findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                        safe(userId), from, to)) {
            out.add(toDto(e));
        }
        return out;
    }

    public Map<TimeCategory, Long> secondsByCategoryForDay(String userId, LocalDate day) {
        Map<TimeCategory, Long> totals = new EnumMap<>(TimeCategory.class);
        for (LifeMapDtos.ActivityEntry e : entriesForDay(userId, day)) {
            totals.merge(e.category() == null ? TimeCategory.CUSTOM : e.category(),
                    e.durationSeconds(), Long::sum);
        }
        Map<TimeCategory, Long> ordered = new LinkedHashMap<>();
        for (TimeCategory c : TimeCategory.values()) {
            if (totals.containsKey(c)) {
                ordered.put(c, totals.get(c));
            }
        }
        return ordered;
    }

    public long totalSecondsForDay(String userId, LocalDate day) {
        long total = 0;
        for (LifeMapDtos.ActivityEntry e : entriesForDay(userId, day)) {
            total += e.durationSeconds();
        }
        return total;
    }

    public int size(String userId) {
        return (int) repository.countByUserId(safe(userId));
    }

    private LifeMapDtos.ActivityEntry toDto(LifeActivityEntry e) {
        return new LifeMapDtos.ActivityEntry(
                e.getEntryId(), e.getStartedAt(), e.getEndedAt(), e.getDurationSeconds(),
                e.getCategory(), e.getAppName(), e.getWindowTitle(), e.getSource());
    }

    private String safe(String s) {
        return s == null ? "anonymous" : s;
    }
}
