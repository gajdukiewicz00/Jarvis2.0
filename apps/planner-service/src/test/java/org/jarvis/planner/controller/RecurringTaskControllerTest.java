package org.jarvis.planner.controller;

import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.service.RecurringOccurrenceService;
import org.jarvis.planner.service.RecurringTaskGenerator;
import org.jarvis.planner.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecurringTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecurringTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecurringOccurrenceService recurringOccurrenceService;

    @MockBean
    private RecurringTaskGenerator recurringTaskGenerator;

    @MockBean
    private TaskService taskService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    private Task occurrence(Long id, TaskStatus status) {
        Task t = new Task();
        t.setId(id);
        t.setTitle("Morning review");
        t.setStatus(status);
        t.setRecurrenceSourceTaskId(1L);
        return t;
    }

    private TaskDto dto(Long id, TaskStatus status) {
        TaskDto dto = new TaskDto();
        dto.setId(id);
        dto.setTitle("Morning review");
        dto.setStatus(status);
        dto.setRecurrenceSourceTaskId(1L);
        return dto;
    }

    @Test
    @DisplayName("PATCH /{id}/skip-occurrence skips the occurrence for the authenticated user")
    void skipOccurrenceMarksTaskSkipped() throws Exception {
        Task skipped = occurrence(5L, TaskStatus.SKIPPED);
        when(recurringOccurrenceService.skipOccurrence("user-1", 5L)).thenReturn(skipped);
        when(taskService.toDto(skipped)).thenReturn(dto(5L, TaskStatus.SKIPPED));

        mockMvc.perform(patch("/api/v1/planner/tasks/5/skip-occurrence").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));
    }

    @Test
    @DisplayName("PATCH /{id}/complete-occurrence completes the occurrence for the authenticated user")
    void completeOccurrenceMarksTaskDone() throws Exception {
        Task completed = occurrence(5L, TaskStatus.DONE);
        when(recurringOccurrenceService.completeOccurrence("user-1", 5L)).thenReturn(completed);
        when(taskService.toDto(completed)).thenReturn(dto(5L, TaskStatus.DONE));

        mockMvc.perform(patch("/api/v1/planner/tasks/5/complete-occurrence").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("PATCH /{id}/skip-occurrence surfaces 404 when the task doesn't exist for this user")
    void skipOccurrencePropagatesNotFound() throws Exception {
        when(recurringOccurrenceService.skipOccurrence("user-1", 404L))
                .thenThrow(new TaskNotFoundException(404L, "user-1"));

        mockMvc.perform(patch("/api/v1/planner/tasks/404/skip-occurrence").principal(authenticatedUser("user-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{id}/generate-next-occurrences materializes the requested count and maps to DTOs")
    void generateNextOccurrencesReturnsGeneratedTasksAsDtos() throws Exception {
        Task occ1 = occurrence(10L, TaskStatus.TODO);
        Task occ2 = occurrence(11L, TaskStatus.TODO);

        when(recurringTaskGenerator.generateNextOccurrences("user-1", 1L, 2)).thenReturn(List.of(occ1, occ2));
        when(taskService.toDto(occ1)).thenReturn(dto(10L, TaskStatus.TODO));
        when(taskService.toDto(occ2)).thenReturn(dto(11L, TaskStatus.TODO));

        mockMvc.perform(post("/api/v1/planner/tasks/1/generate-next-occurrences")
                        .principal(authenticatedUser("user-1"))
                        .param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[1].id").value(11));
    }

    @Test
    @DisplayName("POST /{id}/generate-next-occurrences defaults count to 5 when not provided")
    void generateNextOccurrencesDefaultsCountToFive() throws Exception {
        when(recurringTaskGenerator.generateNextOccurrences(eq("user-1"), eq(1L), eq(5))).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/planner/tasks/1/generate-next-occurrences")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /{id}/generate-next-occurrences surfaces 400 for a non-recurring template")
    void generateNextOccurrencesPropagatesValidationError() throws Exception {
        when(recurringTaskGenerator.generateNextOccurrences("user-1", 1L, 5))
                .thenThrow(new IllegalArgumentException("Task 1 is not a recurring template"));

        mockMvc.perform(post("/api/v1/planner/tasks/1/generate-next-occurrences")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isBadRequest());
    }
}
