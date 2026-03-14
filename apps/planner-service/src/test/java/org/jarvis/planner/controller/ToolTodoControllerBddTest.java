package org.jarvis.planner.controller;

import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.TaskSource;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.jarvis.planner.tooling.dto.CreateTodoRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolTodoController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolTodoControllerBddTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private ToolRequestService toolRequestService;

    @Test
    @DisplayName("Given a fresh tool request when create todo is called then the task is created for the tool user and cached")
    void givenFreshToolRequestWhenCreateTodoThenTaskIsCreatedAndCached() throws Exception {
        when(toolRequestService.hashRequest(any(CreateTodoRequest.class))).thenReturn("hash-1");
        when(toolRequestService.loadCachedResponse(
                eq("key-1"),
                eq("create_todo"),
                eq("user-123"),
                eq("hash-1"),
                eq(TaskDto.class)))
                .thenReturn(Optional.empty());

        TaskDto created = new TaskDto();
        created.setId(55L);
        created.setUserId("user-123");
        created.setTitle("Pay bills");
        created.setSource(TaskSource.AI);
        created.setCreatedBy("ai");
        created.setUpdatedBy("ai");

        when(taskService.createTask(any(TaskDto.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/tools/todo/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pay bills",
                                  "description": "Before Monday",
                                  "tags": ["finance", "urgent"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(55))
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.source").value("AI"));

        ArgumentCaptor<TaskDto> captor = ArgumentCaptor.forClass(TaskDto.class);
        verify(taskService).createTask(captor.capture());

        TaskDto submittedTask = captor.getValue();
        assertEquals("user-123", submittedTask.getUserId());
        assertEquals("Pay bills", submittedTask.getTitle());
        assertEquals(TaskSource.AI, submittedTask.getSource());
        assertEquals("ai", submittedTask.getCreatedBy());
        assertEquals("ai", submittedTask.getUpdatedBy());
        assertEquals(List.of("finance", "urgent"), submittedTask.getTags());

        verify(toolRequestService).storeResponse("key-1", "create_todo", "user-123", "hash-1", created);
    }

    @Test
    @DisplayName("Given a cached tool response when create todo is retried then the cached task is returned without creating another one")
    void givenCachedResponseWhenCreateTodoIsRetriedThenCachedTaskIsReturned() throws Exception {
        when(toolRequestService.hashRequest(any(CreateTodoRequest.class))).thenReturn("hash-1");

        TaskDto cached = new TaskDto();
        cached.setId(55L);
        cached.setUserId("user-123");
        cached.setTitle("Pay bills");
        cached.setSource(TaskSource.AI);

        when(toolRequestService.loadCachedResponse(
                eq("key-1"),
                eq("create_todo"),
                eq("user-123"),
                eq("hash-1"),
                eq(TaskDto.class)))
                .thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/tools/todo/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Pay bills"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(55))
                .andExpect(jsonPath("$.title").value("Pay bills"));

        verify(taskService, never()).createTask(any(TaskDto.class));
        verify(toolRequestService, never()).storeResponse(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any());
    }
}
