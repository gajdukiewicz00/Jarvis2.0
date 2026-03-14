package org.jarvis.planner.controller;

import jakarta.servlet.Filter;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.planner.config.SecurityConfig;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.jarvis.planner.tooling.ToolUserIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolTodoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class ToolTodoControllerSecurityTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ToolUserIdFilter toolUserIdFilter;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private TaskService taskService;

    @MockBean
    private ToolRequestService toolRequestService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .addFilter(toolUserIdFilter)
                .build();
    }

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

        verify(taskService).getTasks(eq("user-123"), eq(null));
    }

    @Test
    void listWithValidServiceTokenButMissingUserHeaderReturnsBadRequest() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(post("/api/v1/tools/todo/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Service-Token", serviceToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskService);
    }
}
