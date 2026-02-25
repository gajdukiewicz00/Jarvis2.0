package org.jarvis.planner.controller;

import org.jarvis.planner.config.GatewayUserAuthenticationFilter;
import org.jarvis.planner.config.SecurityConfig;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.service.TaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import({SecurityConfig.class, GatewayUserAuthenticationFilter.class})
class TaskControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @Test
    void getTasksWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/planner/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTasksUsesAuthenticatedUserContext() throws Exception {
        when(taskService.getTasks(eq("user-123"), eq(null))).thenReturn(List.of(new TaskDto()));

        mockMvc.perform(get("/api/v1/planner/tasks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer fake-token")
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).getTasks(userIdCaptor.capture(), eq(null));
        assertThat(userIdCaptor.getValue()).isEqualTo("user-123");
    }
}
