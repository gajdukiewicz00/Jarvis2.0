package org.jarvis.apigateway.security;

import org.jarvis.common.safety.SystemPanicState;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.apigateway.controller.AuthProxyController;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.jarvis.common.JarvisCommonAutoConfiguration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuthProxyController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        properties = {
                "jarvis.jwt.enabled=true",
                "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.issuer=jarvis",
                "services.security.url=http://security.test",
                "service.jwt.secret=service-secret-01234567890123456789012345678901"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class, JarvisCommonAutoConfiguration.class, SystemPanicState.class})
@ActiveProfiles("test")
class AuthProxySecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @MockBean
    private DownstreamProxyService downstreamProxyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void logoutPathIsPublicAndProxied() throws Exception {
        when(downstreamProxyService.forward(any(HttpServletRequest.class),
                eq("security-service"),
                eq("http://security.test"),
                eq("/auth"),
                eq("/auth")))
                .thenReturn(ResponseEntity.noContent().build());

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(downstreamProxyService).forward(any(HttpServletRequest.class),
                eq("security-service"),
                eq("http://security.test"),
                eq("/auth"),
                eq("/auth"));
    }

    @Test
    void mePathStillRequiresAccessToken() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_TOKEN"));

        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class),
                any(), any(), any(), any());
    }
}
