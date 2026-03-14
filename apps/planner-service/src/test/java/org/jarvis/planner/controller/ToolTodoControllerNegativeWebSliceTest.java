package org.jarvis.planner.controller;

import org.jarvis.common.exception.IdempotencyConflictException;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.jarvis.planner.tooling.dto.UpdateTodoRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolTodoController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolTodoControllerNegativeWebSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private ToolRequestService toolRequestService;

    @Test
    void createTodoWithMissingTitleReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/tools/todo/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Before Monday"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.details[0].field").value("title"));

        verifyNoInteractions(taskService, toolRequestService);
    }

    @Test
    void createTodoWithUnknownFieldReturnsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/tools/todo/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pay bills",
                                  "rogue": "value"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.message", containsString("Unknown field: rogue")));

        verifyNoInteractions(taskService, toolRequestService);
    }

    @Test
    void createTodoWithInvalidPriorityEnumReturnsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/tools/todo/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pay bills",
                                  "priority": "ASAP"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.message", containsString("TaskPriority")));

        verifyNoInteractions(taskService, toolRequestService);
    }

    @Test
    void updateTodoWithIdempotencyConflictReturnsConflict() throws Exception {
        when(toolRequestService.hashRequest(any(UpdateTodoRequest.class))).thenReturn("hash-1");
        when(toolRequestService.loadCachedResponse(
                eq("key-1"),
                eq("update_todo"),
                eq("user-123"),
                eq("hash-1"),
                eq(TaskDto.class)))
                .thenThrow(new IdempotencyConflictException("Idempotency key reused with different request payload"));

        mockMvc.perform(post("/api/v1/tools/todo/update")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 7,
                                  "status": "DONE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"))
                .andExpect(jsonPath("$.message", containsString("different request payload")));

        verifyNoInteractions(taskService);
    }
}
