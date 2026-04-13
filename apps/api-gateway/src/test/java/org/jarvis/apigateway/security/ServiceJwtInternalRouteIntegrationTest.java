package org.jarvis.apigateway.security;

import org.jarvis.apigateway.controller.PcControlInternalController;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.jarvis.common.JarvisCommonAutoConfiguration;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.Filter;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = PcControlInternalController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        properties = {
                "jarvis.jwt.enabled=true",
                "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.issuer=jarvis",
                "service.jwt.secret=service-secret-01234567890123456789012345678901"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({ SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class, JarvisCommonAutoConfiguration.class })
@ActiveProfiles("test")
class ServiceJwtInternalRouteIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private PcControlWebSocketHandler webSocketHandler;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void internalPcControlRouteAcceptsServiceJwt() throws Exception {
        when(webSocketHandler.dispatchPcAction(
                org.mockito.ArgumentMatchers.eq("NOTIFY"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq("user-123"),
                org.mockito.ArgumentMatchers.eq(null)))
                .thenReturn(new PcControlWebSocketHandler.DispatchResult(
                        "req-1",
                        "NOTIFY",
                        "executed",
                        true,
                        true,
                        true,
                        false,
                        null,
                        1,
                        1,
                        1,
                        0,
                        null,
                        "user-123"));

        String serviceToken = serviceJwtProvider.createToken("planner-service", List.of("SVC_INTERNAL"));

        mockMvc.perform(post("/internal/pc-control/action")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "NOTIFY",
                                  "userId": "user-123",
                                  "params": {
                                    "title": "Smoke",
                                    "message": "Reminder"
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        verify(webSocketHandler).dispatchPcAction(
                org.mockito.ArgumentMatchers.eq("NOTIFY"),
                argThat(jsonNode -> jsonNode != null
                        && "Smoke".equals(jsonNode.path("title").asText())
                        && "Reminder".equals(jsonNode.path("message").asText())),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq("user-123"),
                org.mockito.ArgumentMatchers.eq(null));
    }

    @Test
    void internalPcControlRouteRejectsServiceJwtInAuthorizationHeader() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("planner-service", List.of("SVC_INTERNAL"));

        mockMvc.perform(post("/internal/pc-control/action")
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "NOTIFY",
                                  "userId": "user-123",
                                  "params": {
                                    "title": "Smoke",
                                    "message": "Reminder"
                                  }
                                }
                                """))
                .andExpect(status().isUnauthorized());

        verify(webSocketHandler, never()).dispatchPcAction(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
