package org.jarvis.apigateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.jarvis.apigateway.client.OrchestratorClient;
import org.jarvis.apigateway.controller.OrchestratorProxyController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = OrchestratorProxyController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        },
        properties = {
        "jwt.enabled=true",
        "jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
        "jwt.issuer=jarvis",
        "logging.level.org.jarvis.apigateway.security.JwtAuthenticationFilter=DEBUG"
})
@AutoConfigureMockMvc
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class })
@ActiveProfiles("test")
class JwtAuthenticationFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrchestratorClient orchestratorClient;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orchestratorClient);
    }

    @Test
    void requestWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer invalid.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orchestratorClient);
    }

    @Test
    void requestWithExpiredTokenReturns401() throws Exception {
        String expiredToken = buildToken(Instant.now().minusSeconds(60));

        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orchestratorClient);
    }

    @Test
    void requestWithValidTokenReturns200AndProxiesRequest() throws Exception {
        String validToken = buildToken(Instant.now().plusSeconds(300));

        when(orchestratorClient.execute(anyMap())).thenReturn(ResponseEntity.ok("ok"));

        mockMvc.perform(post("/api/v1/orchestrator/execute")
                        .header("Authorization", "Bearer " + validToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));

        verify(orchestratorClient).execute(Map.of("text", "hello"));
    }

    private String buildToken(Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", "USER")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }
}
