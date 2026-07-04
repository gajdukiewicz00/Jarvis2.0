package org.jarvis.planner.controller;

import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.jarvis.planner.tooling.dto.CompleteTodoRequest;
import org.jarvis.planner.tooling.dto.ListTodosRequest;
import org.jarvis.planner.tooling.dto.UpdateTodoRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolTodoController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolTodoControllerUpdateCompleteListTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private ToolRequestService toolRequestService;

    private TaskDto taskDto(Long id, String title) {
        TaskDto dto = new TaskDto();
        dto.setId(id);
        dto.setUserId("user-123");
        dto.setTitle(title);
        return dto;
    }

    @Test
    @DisplayName("Given a fresh update request when update todo is called then the task is updated and cached")
    void givenFreshRequestWhenUpdateTodoThenTaskIsUpdatedAndCached() throws Exception {
        when(toolRequestService.hashRequest(any(UpdateTodoRequest.class))).thenReturn("hash-u1");
        when(toolRequestService.loadCachedResponse(
                eq("key-u1"), eq("update_todo"), eq("user-123"), eq("hash-u1"), eq(TaskDto.class)))
                .thenReturn(Optional.empty());

        TaskDto updated = taskDto(7L, "Pay bills sooner");
        when(taskService.updateTask(eq(7L), eq("user-123"), any(TaskDto.class))).thenReturn(updated);

        mockMvc.perform(post("/api/v1/tools/todo/update")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 7,
                                  "title": "Pay bills sooner",
                                  "status": "IN_PROGRESS"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.title").value("Pay bills sooner"));

        verify(taskService).updateTask(eq(7L), eq("user-123"), any(TaskDto.class));
        verify(toolRequestService).storeResponse("key-u1", "update_todo", "user-123", "hash-u1", updated);
    }

    @Test
    @DisplayName("Given a cached update response when update todo is retried then the cached task is returned")
    void givenCachedResponseWhenUpdateTodoRetriedThenCachedTaskReturned() throws Exception {
        when(toolRequestService.hashRequest(any(UpdateTodoRequest.class))).thenReturn("hash-u2");
        TaskDto cached = taskDto(7L, "Pay bills sooner");
        when(toolRequestService.loadCachedResponse(
                eq("key-u2"), eq("update_todo"), eq("user-123"), eq("hash-u2"), eq(TaskDto.class)))
                .thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/tools/todo/update")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-u2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 7,
                                  "status": "IN_PROGRESS"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(taskService, never()).updateTask(any(), any(), any());
    }

    @Test
    @DisplayName("Given a fresh complete request when complete todo is called then the task is completed and cached")
    void givenFreshRequestWhenCompleteTodoThenTaskIsCompletedAndCached() throws Exception {
        when(toolRequestService.hashRequest(any(CompleteTodoRequest.class))).thenReturn("hash-c1");
        when(toolRequestService.loadCachedResponse(
                eq("key-c1"), eq("complete_todo"), eq("user-123"), eq("hash-c1"), eq(TaskDto.class)))
                .thenReturn(Optional.empty());

        TaskDto completed = taskDto(8L, "Done task");
        completed.setStatus(TaskStatus.DONE);
        when(taskService.completeTask(8L, "user-123")).thenReturn(completed);

        mockMvc.perform(post("/api/v1/tools/todo/complete")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(8))
                .andExpect(jsonPath("$.status").value("DONE"));

        verify(taskService).completeTask(8L, "user-123");
        verify(toolRequestService).storeResponse("key-c1", "complete_todo", "user-123", "hash-c1", completed);
    }

    @Test
    @DisplayName("Given a cached complete response when complete todo is retried then the cached task is returned")
    void givenCachedResponseWhenCompleteTodoRetriedThenCachedTaskReturned() throws Exception {
        when(toolRequestService.hashRequest(any(CompleteTodoRequest.class))).thenReturn("hash-c2");
        TaskDto cached = taskDto(8L, "Done task");
        when(toolRequestService.loadCachedResponse(
                eq("key-c2"), eq("complete_todo"), eq("user-123"), eq("hash-c2"), eq(TaskDto.class)))
                .thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/tools/todo/complete")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-c2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(8));

        verify(taskService, never()).completeTask(any(), any());
    }

    @Test
    @DisplayName("listTodos with no filters returns all tasks unfiltered")
    void listTodosWithNoFiltersReturnsAllTasks() throws Exception {
        when(taskService.getTasks("user-123", null)).thenReturn(List.of(taskDto(1L, "A"), taskDto(2L, "B")));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("listTodos filters out tasks outside the requested due-date range or with no due date")
    void listTodosFiltersByDueDateRange() throws Exception {
        TaskDto inRange = taskDto(1L, "In range");
        inRange.setDueDate(Instant.parse("2026-06-15T00:00:00Z"));
        TaskDto beforeFrom = taskDto(2L, "Too early");
        beforeFrom.setDueDate(Instant.parse("2026-05-01T00:00:00Z"));
        TaskDto afterTo = taskDto(3L, "Too late");
        afterTo.setDueDate(Instant.parse("2026-07-01T00:00:00Z"));
        TaskDto noDueDate = taskDto(4L, "No due date");

        when(taskService.getTasks("user-123", null))
                .thenReturn(List.of(inRange, beforeFrom, afterTo, noDueDate));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "from": "2026-06-01T00:00:00Z",
                                  "to": "2026-06-30T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("listTodos filters out tasks missing a required tag or with no tags")
    void listTodosFiltersByTags() throws Exception {
        TaskDto matching = taskDto(1L, "Has tag");
        matching.setTags(List.of("urgent", "finance"));
        TaskDto missingTag = taskDto(2L, "Missing tag");
        missingTag.setTags(List.of("finance"));
        TaskDto noTags = taskDto(3L, "No tags");

        when(taskService.getTasks("user-123", null)).thenReturn(List.of(matching, missingTag, noTags));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tags": ["urgent"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("listTodos forwards the status filter to the task service")
    void listTodosForwardsStatusFilter() throws Exception {
        when(taskService.getTasks("user-123", TaskStatus.DONE)).thenReturn(List.of(taskDto(1L, "Done task")));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DONE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(taskService).getTasks("user-123", TaskStatus.DONE);
    }
}
