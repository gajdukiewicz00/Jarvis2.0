package org.jarvis.lifetracker.lifemap;

import org.jarvis.lifetracker.domain.LifeActivityEntry;
import org.jarvis.lifetracker.repository.LifeActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryActivityStoreTest {

    private final List<LifeActivityEntry> db = new ArrayList<>();
    private final LifeActivityRepository repo = mock(LifeActivityRepository.class);
    private final InMemoryActivityStore store = new InMemoryActivityStore(repo);

    @BeforeEach
    void setup() {
        db.clear();
        when(repo.save(any(LifeActivityEntry.class))).thenAnswer(inv -> {
            LifeActivityEntry e = inv.getArgument(0);
            db.add(e);
            return e;
        });
        when(repo.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                anyString(), any(), any())).thenAnswer(inv -> {
            String uid = inv.getArgument(0);
            Instant from = inv.getArgument(1);
            Instant to = inv.getArgument(2);
            return db.stream()
                    .filter(e -> e.getUserId().equals(uid)
                            && !e.getStartedAt().isBefore(from) && e.getStartedAt().isBefore(to))
                    .sorted(Comparator.comparing(LifeActivityEntry::getStartedAt))
                    .collect(Collectors.toList());
        });
        when(repo.countByUserId(anyString())).thenAnswer(inv ->
                db.stream().filter(e -> e.getUserId().equals(inv.getArgument(0))).count());
    }

    @Test
    void recordReturnsEntryWithDuration() {
        Instant start = Instant.parse("2026-05-01T08:00:00Z");
        Instant end = Instant.parse("2026-05-01T08:30:00Z");
        var entry = store.record("owner", "IntelliJ", "Project", start, end, null,
                TimeCategory.WORK, "agent");
        assertThat(entry.entryId()).startsWith("act-");
        assertThat(entry.durationSeconds()).isEqualTo(30 * 60);
    }

    @Test
    void durationOverrideTakesPrecedence() {
        var entry = store.record("owner", "ZSH", "build.log",
                Instant.now(), Instant.now(), 42L, TimeCategory.WORK, "agent");
        assertThat(entry.durationSeconds()).isEqualTo(42);
    }

    @Test
    void totalsByCategoryAggregatePerDay() {
        ZoneId zone = ZoneId.systemDefault();
        Instant base = LocalDate.of(2026, 5, 1).atStartOfDay(zone).toInstant().plusSeconds(8 * 3600);
        store.record("owner", "IntelliJ", null, base, base.plusSeconds(1800), null,
                TimeCategory.WORK, "agent");
        store.record("owner", "Spotify", null, base, base.plusSeconds(900), null,
                TimeCategory.REST, "agent");
        store.record("owner", "IntelliJ", null, base, base.plusSeconds(600), null,
                TimeCategory.WORK, "agent");

        var totals = store.secondsByCategoryForDay("owner", LocalDate.of(2026, 5, 1));
        assertThat(totals.get(TimeCategory.WORK)).isEqualTo(2400L);
        assertThat(totals.get(TimeCategory.REST)).isEqualTo(900L);
        assertThat(store.totalSecondsForDay("owner", LocalDate.of(2026, 5, 1))).isEqualTo(3300L);
    }

    @Test
    void entriesAreScopedPerUser() {
        store.record("owner", "X", null, Instant.now(), Instant.now(), 1L,
                TimeCategory.WORK, "agent");
        store.record("guest", "Y", null, Instant.now(), Instant.now(), 1L,
                TimeCategory.REST, "agent");
        assertThat(store.size("owner")).isEqualTo(1);
        assertThat(store.size("guest")).isEqualTo(1);
    }

    @Test
    void unknownDayReturnsEmptyMap() {
        assertThat(store.secondsByCategoryForDay("owner", LocalDate.of(1990, 1, 1))).isEmpty();
        assertThat(store.entriesForDay("owner", LocalDate.of(1990, 1, 1))).isEmpty();
    }
}
