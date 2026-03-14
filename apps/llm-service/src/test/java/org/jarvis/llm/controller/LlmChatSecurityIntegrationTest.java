package org.jarvis.llm.controller;

import jakarta.servlet.Filter;
import org.jarvis.common.JarvisCommonAutoConfiguration;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.llm.config.SecurityConfig;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.model.Emotion;
import org.jarvis.llm.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = LlmRestController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JarvisCommonAutoConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "service.jwt.secret=service-secret-01234567890123456789012345678901",
        "service.jwt.required=true"
})
class LlmChatSecurityIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void chatRequiresInternalAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/llm/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "messages": [
                                    {"role": "user", "content": "Привет"}
                                  ]
                                }
                                """))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isIn(401, 403));
    }

    @Test
    void chatWithDelegatedUserUsesResolvedUserContext() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("orchestrator", List.of("SVC_INTERNAL"));
        when(llmService.processMessage(eq("session-1"), eq("user-123"), eq("Привет"), eq("corr-1")))
                .thenReturn(new ChatResponseDto("ok", Map.of("total", 2), "stub", 1, Emotion.NEUTRAL));

        mockMvc.perform(post("/api/v1/llm/chat")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123")
                        .header("X-Correlation-ID", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "messages": [
                                    {"role": "user", "content": "Привет"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("ok"));

        verify(llmService).processMessage("session-1", "user-123", "Привет", "corr-1");
    }

    @Test
    void chatWithServiceJwtButNoDelegatedUserFallsBackToSessionDerivedIdentity() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("planner-service", List.of("SVC_INTERNAL"));
        when(llmService.processMessage(eq("user-123-session"), eq((String) null), eq("Привет"), any()))
                .thenReturn(new ChatResponseDto("ok", Map.of("total", 2), "stub", 1, Emotion.NEUTRAL));

        mockMvc.perform(post("/api/v1/llm/chat")
                        .header("X-Service-Token", serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "user-123-session",
                                  "messages": [
                                    {"role": "user", "content": "Привет"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        verify(llmService).processMessage(eq("user-123-session"), eq((String) null), eq("Привет"), any());
    }
}
