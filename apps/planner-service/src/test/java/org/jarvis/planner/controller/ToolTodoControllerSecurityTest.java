package org.jarvis.planner.controller;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.planner.config.SecurityConfig;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolTodoController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class ToolTodoControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private TaskService taskService;

    @MockBean
    private ToolRequestService toolRequestService;

    @Test
    void listWithoutValidJwtReturnsUnauthorizedOrForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer invalid.jwt.token")
                        .header("X-User-Id", "forged-user"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value()));
    }

    @Test
    void listUsesDelegatedPrincipalUserId() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));
        when(taskService.getTasks(eq("user-123"), eq(null))).thenReturn(List.of(new TaskDto()));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).getTasks(userIdCaptor.capture(), eq(null));
        assertThat(userIdCaptor.getValue()).isEqualTo("user-123");
    }
}
