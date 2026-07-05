package org.jarvis.lifetracker.controller;

import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.HabitStreakDTO;
import org.jarvis.lifetracker.dto.WellnessSummaryDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.jarvis.lifetracker.service.WellnessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WellnessController.class)
@AutoConfigureMockMvc(addFilters = false)
class WellnessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WellnessLogRepository repository;

    @MockBean
    private WellnessService wellnessService;

    @Test
    void logWithoutTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/life/wellness/log")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": 5.0}
                                """))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }

    @Test
    void logSavesEntryWithProvidedDay() throws Exception {
        when(repository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/wellness/log")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "MOOD", "value": 4.0, "note": "good day", "day": "2026-03-10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MOOD"))
                .andExpect(jsonPath("$.userId").value("user-1"));
    }

    @Test
    void logDefaultsDayToTodayWhenMissing() throws Exception {
        when(repository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/wellness/log")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "STEPS", "value": 1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value(LocalDate.now().toString()));
    }

    @Test
    void dayReturnsEntriesForRequestedDate() throws Exception {
        WellnessLog log = new WellnessLog();
        log.setId(1L);
        when(repository.findByUserIdAndDayOrderByLoggedAtAsc("user-1", LocalDate.of(2026, 3, 10)))
                .thenReturn(List.of(log));

        mockMvc.perform(get("/api/v1/life/wellness/day")
                        .header("X-User-Id", "user-1")
                        .param("date", "2026-03-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void dayDefaultsToTodayWhenDateMissing() throws Exception {
        when(repository.findByUserIdAndDayOrderByLoggedAtAsc(eq("user-1"), any(LocalDate.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/life/wellness/day").header("X-User-Id", "user-1"))
                .andExpect(status().isOk());

        verify(repository).findByUserIdAndDayOrderByLoggedAtAsc(eq("user-1"), eq(LocalDate.now()));
    }

    @Test
    void trendReturnsEntriesForType() throws Exception {
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.WEIGHT))
                .thenReturn(List.of(new WellnessLog()));

        mockMvc.perform(get("/api/v1/life/wellness/trend")
                        .header("X-User-Id", "user-1")
                        .param("type", "WEIGHT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void recentReturnsUpToTop200Entries() throws Exception {
        when(repository.findTop200ByUserIdOrderByLoggedAtDesc("user-1")).thenReturn(List.of(new WellnessLog()));

        mockMvc.perform(get("/api/v1/life/wellness/recent").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void healthEntrySavesSleepAndStepsAndReturnsCounts() throws Exception {
        when(repository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/wellness/health-entry")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sleepHours": 7.5, "steps": 8000, "date": "2026-03-10T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(2))
                .andExpect(jsonPath("$.day").value("2026-03-10"));

        verify(repository, times(2)).save(any(WellnessLog.class));
    }

    @Test
    void healthEntryWithNoFieldsSavesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/life/wellness/health-entry")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(0));

        verify(repository, never()).save(any());
    }

    @Test
    void healthEntryWithUnparseableDateFallsBackToToday() throws Exception {
        when(repository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/wellness/health-entry")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sleepHours": 6.0, "date": "not-a-date"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value(LocalDate.now().toString()));
    }

    @Test
    void habitStreakWithoutNameReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/life/wellness/habit/streak").header("X-User-Id", "user-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void habitStreakDelegatesToWellnessService() throws Exception {
        when(wellnessService.habitStreak("user-1", "Meditate"))
                .thenReturn(new HabitStreakDTO("Meditate", 5, 7, LocalDate.now(), true, 10));

        mockMvc.perform(get("/api/v1/life/wellness/habit/streak")
                        .header("X-User-Id", "user-1")
                        .param("name", "Meditate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStreak").value(5))
                .andExpect(jsonPath("$.longestStreak").value(7));
    }

    @Test
    void habitsListsAllStreaksForUser() throws Exception {
        when(wellnessService.listHabitStreaks("user-1")).thenReturn(List.of(
                new HabitStreakDTO("Meditate", 3, 3, LocalDate.now(), true, 3),
                new HabitStreakDTO("Exercise", 0, 2, LocalDate.now().minusDays(5), false, 2)));

        mockMvc.perform(get("/api/v1/life/wellness/habits").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void summaryDefaultsToTrailingThirtyDaysWhenNoRangeGiven() throws Exception {
        LocalDate today = LocalDate.now();
        when(wellnessService.summary("user-1", WellnessType.WEIGHT, today.minusDays(29), today))
                .thenReturn(new WellnessSummaryDTO(WellnessType.WEIGHT, today.minusDays(29), today,
                        2, 79.5, 79.0, 80.0, 79.0, today));

        mockMvc.perform(get("/api/v1/life/wellness/summary")
                        .header("X-User-Id", "user-1")
                        .param("type", "WEIGHT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average").value(79.5));
    }

    @Test
    void summaryUsesExplicitFromAndTo() throws Exception {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        when(wellnessService.summary("user-1", WellnessType.MOOD, from, to))
                .thenReturn(new WellnessSummaryDTO(WellnessType.MOOD, from, to, 1, 4.0, 4.0, 4.0, 4.0, to));

        mockMvc.perform(get("/api/v1/life/wellness/summary")
                        .header("X-User-Id", "user-1")
                        .param("type", "MOOD")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryCount").value(1));

        verify(wellnessService).summary("user-1", WellnessType.MOOD, from, to);
    }
}
