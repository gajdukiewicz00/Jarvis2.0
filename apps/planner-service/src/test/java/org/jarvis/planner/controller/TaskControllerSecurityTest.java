package org.jarvis.planner.controller;

import jakarta.servlet.Filter;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.planner.config.SecurityConfig;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class TaskControllerSecurityTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void getTasksWithoutTokenReturns401OrForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/planner/tasks"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(401, 403));
    }

    @Test
    void getTasksUsesAuthenticatedUserContext() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));
        when(taskService.getTasks(eq("user-123"), eq(null))).thenReturn(List.of(new TaskDto()));

        mockMvc.perform(get("/api/v1/planner/tasks")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).getTasks(userIdCaptor.capture(), eq(null));
        assertThat(userIdCaptor.getValue()).isEqualTo("user-123");
    }
}
