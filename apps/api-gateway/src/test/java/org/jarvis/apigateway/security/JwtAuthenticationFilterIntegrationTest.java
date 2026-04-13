package org.jarvis.apigateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.apigateway.controller.OrchestratorProxyController;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.jarvis.common.JarvisCommonAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = OrchestratorProxyController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        properties = {
                "jarvis.jwt.enabled=true",
                "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.issuer=jarvis",
                "services.orchestrator.url=http://orchestrator.test",
                "service.jwt.secret=service-secret-01234567890123456789012345678901",
                "logging.level.org.jarvis.apigateway.security.JwtAuthFilter=DEBUG"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class, JarvisCommonAutoConfiguration.class})
@ActiveProfiles("test")
class JwtAuthenticationFilterIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Value("${jarvis.jwt.secret}")
    private String jwtSecret;

    @MockBean
    private DownstreamProxyService downstreamProxyService;

    private MockMvc mockMvc;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_TOKEN"));

        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), any(), any());
    }

    @Test
    void requestWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer invalid.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MALFORMED_TOKEN"));

        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), any(), any());
    }

    @Test
    void requestWithExpiredTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer " + buildToken(Instant.now().minusSeconds(60)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_EXPIRED"));

        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), any(), any());
    }

    @Test
    void requestWithValidTokenAddsDownstreamUserHeaders() throws Exception {
        when(downstreamProxyService.forward(any(HttpServletRequest.class), eq("orchestrator"), eq("http://orchestrator.test")))
                .thenReturn(ResponseEntity.ok("ok".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer " + buildToken(Instant.now().plusSeconds(300)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("ok".getBytes(StandardCharsets.UTF_8)));

        verify(downstreamProxyService).forward(
                argThat(request -> "user-1".equals(request.getHeader("X-User-Id"))
                        && "test-user".equals(request.getHeader("X-Username"))
                        && "USER".equals(request.getHeader("X-User-Roles"))),
                eq("orchestrator"),
                eq("http://orchestrator.test"));
    }

    @Test
    void requestWithRefreshTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer " + buildRefreshToken(Instant.now().plusSeconds(300)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN_TYPE"));

        verify(downstreamProxyService, never()).forward(any(HttpServletRequest.class), any(), any());
    }

    private String buildToken(Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", "USER")
                .claim("type", "access")
                .issuer("jarvis")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    private String buildRefreshToken(Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("type", "refresh")
                .issuer("jarvis")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }
}
