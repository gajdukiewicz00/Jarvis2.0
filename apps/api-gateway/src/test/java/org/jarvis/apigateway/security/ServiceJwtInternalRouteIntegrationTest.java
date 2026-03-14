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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(webSocketHandler.hasConnectedClients()).thenReturn(true);
        when(webSocketHandler.sendPcActionToUser(eq("user-123"), eq("NOTIFY"), any())).thenReturn(1);

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

        verify(webSocketHandler).sendPcActionToUser(eq("user-123"), eq("NOTIFY"), any());
    }
}
