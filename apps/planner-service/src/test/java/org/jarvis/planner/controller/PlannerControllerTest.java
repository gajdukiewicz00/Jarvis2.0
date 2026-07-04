package org.jarvis.planner.controller;

import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.DailyPlanGenerator;
import org.jarvis.planner.service.RecommendationEngine;
import org.jarvis.planner.service.WeeklyPlanGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlannerController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlannerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DailyPlanGenerator dailyPlanGenerator;

    @MockBean
    private WeeklyPlanGenerator weeklyPlanGenerator;

    @MockBean
    private RecommendationEngine recommendationEngine;

    @MockBean
    private TaskRepository taskRepository;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    private Task task(Long id, String title, Instant dueDate) {
        Task t = new Task();
        t.setId(id);
        t.setTitle(title);
        t.setPriority(TaskPriority.HIGH);
        t.setDueDate(dueDate);
        return t;
    }

    @Test
    @DisplayName("GET /daily without a date param defaults to today")
    void getDailyPlanWithoutDateDefaultsToToday() throws Exception {
        DailyPlanDto plan = new DailyPlanDto();
        plan.setUserId("user-1");
        plan.setDate(LocalDate.now());
        when(dailyPlanGenerator.generatePlan(eq("user-1"), any(LocalDate.class))).thenReturn(plan);

        mockMvc.perform(get("/api/v1/planner/daily").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"));

        verify(dailyPlanGenerator).generatePlan(eq("user-1"), eq(LocalDate.now()));
    }

    @Test
    @DisplayName("GET /daily with an explicit date forwards it to the generator")
    void getDailyPlanWithExplicitDateForwardsIt() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 15);
        DailyPlanDto plan = new DailyPlanDto();
        plan.setUserId("user-1");
        plan.setDate(date);
        when(dailyPlanGenerator.generatePlan("user-1", date)).thenReturn(plan);

        mockMvc.perform(get("/api/v1/planner/daily")
                        .principal(authenticatedUser("user-1"))
                        .param("date", "2026-01-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-01-15"));

        verify(dailyPlanGenerator).generatePlan("user-1", date);
    }

    @Test
    @DisplayName("GET /weekly returns the weekly plan for the authenticated user")
    void getWeeklyPlanReturnsPlan() throws Exception {
        when(weeklyPlanGenerator.generateWeeklyPlan("user-1"))
                .thenReturn(Map.of("userId", "user-1", "totalTasks", 3));

        mockMvc.perform(get("/api/v1/planner/weekly").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.totalTasks").value(3));
    }

    @Test
    @DisplayName("GET /recommendations returns generated recommendations")
    void getRecommendationsReturnsList() throws Exception {
        when(recommendationEngine.generateRecommendations("user-1"))
                .thenReturn(List.of(new RecommendationDto(null, "Take a break", TaskPriority.LOW)));

        mockMvc.perform(get("/api/v1/planner/recommendations").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Take a break"));
    }

    @Test
    @DisplayName("GET /focus with no active tasks reports nothing urgent")
    void getFocusWithNoActiveTasksReportsNothing() throws Exception {
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/focus").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasFocus").value(false))
                .andExpect(jsonPath("$.message").value("Никаких срочных задач, сэр. Можно выдохнуть."));
    }

    @Test
    @DisplayName("GET /focus with active tasks reports the top task")
    void getFocusWithActiveTasksReportsTopTask() throws Exception {
        Task top = task(1L, "Ship release", Instant.parse("2026-08-01T00:00:00Z"));
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(top));

        mockMvc.perform(get("/api/v1/planner/focus").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasFocus").value(true))
                .andExpect(jsonPath("$.taskId").value(1))
                .andExpect(jsonPath("$.title").value("Ship release"))
                .andExpect(jsonPath("$.openTasks").value(1))
                .andExpect(jsonPath("$.message").value("Главное сейчас, сэр: Ship release"));
    }

    @Test
    @DisplayName("GET /evening-review with overdue tasks reports overdue count")
    void eveningReviewWithOverdueTasksReportsOverdueCount() throws Exception {
        Task overdue = task(2L, "Late task", Instant.now().minus(1, ChronoUnit.DAYS));
        when(taskRepository.countByUserIdAndStatus("user-1", TaskStatus.DONE)).thenReturn(4L);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(overdue));

        mockMvc.perform(get("/api/v1/planner/evening-review").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doneTotal").value(4))
                .andExpect(jsonPath("$.stillOpen").value(1))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.message").value("Вечерний обзор, сэр: просрочено 1, открыто 1."));
    }

    @Test
    @DisplayName("GET /evening-review with no overdue tasks reports a clean review")
    void eveningReviewWithNoOverdueTasksReportsCleanReview() throws Exception {
        Task upcoming = task(3L, "Future task", Instant.now().plus(3, ChronoUnit.DAYS));
        when(taskRepository.countByUserIdAndStatus("user-1", TaskStatus.DONE)).thenReturn(2L);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(upcoming));

        mockMvc.perform(get("/api/v1/planner/evening-review").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(0))
                .andExpect(jsonPath("$.message").value("Вечерний обзор, сэр: открыто 1 задач, просрочек нет."));
    }

    @Test
    @DisplayName("GET /health reports the service is healthy")
    void healthReportsHealthy() throws Exception {
        mockMvc.perform(get("/api/v1/planner/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("Planner service is healthy"));
    }
}
