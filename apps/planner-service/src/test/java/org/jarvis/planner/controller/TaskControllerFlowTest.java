package org.jarvis.planner.controller;

import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class TaskControllerFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("POST /api/v1/planner/tasks uses the authenticated user instead of body userId")
    void createTaskUsesAuthenticatedUserContext() throws Exception {
        TaskDto created = new TaskDto();
        created.setId(10L);
        created.setUserId("user-123");
        created.setTitle("Pay bills");
        created.setStatus(TaskStatus.TODO);

        when(taskService.createTask(any(TaskDto.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/planner/tasks")
                        .principal(authenticatedUser("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "forged-user",
                                  "title": "Pay bills",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.title").value("Pay bills"));

        ArgumentCaptor<TaskDto> captor = ArgumentCaptor.forClass(TaskDto.class);
        verify(taskService).createTask(captor.capture());

        assertEquals("user-123", captor.getValue().getUserId());
        assertEquals("Pay bills", captor.getValue().getTitle());
    }

    @Test
    @DisplayName("GET /api/v1/planner/tasks forwards the authenticated user and enum filter")
    void getTasksForwardsAuthenticatedUserAndStatusFilter() throws Exception {
        TaskDto task = new TaskDto();
        task.setId(1L);
        task.setTitle("Focus block");
        task.setStatus(TaskStatus.TODO);

        when(taskService.getTasks("user-123", TaskStatus.TODO)).thenReturn(List.of(task));

        mockMvc.perform(get("/api/v1/planner/tasks")
                        .principal(authenticatedUser("user-123"))
                        .param("status", "TODO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Focus block"))
                .andExpect(jsonPath("$[0].status").value("TODO"));

        verify(taskService).getTasks("user-123", TaskStatus.TODO);
    }
}
