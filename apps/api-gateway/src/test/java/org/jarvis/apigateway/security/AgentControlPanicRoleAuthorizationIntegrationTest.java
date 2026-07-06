package org.jarvis.apigateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import org.jarvis.apigateway.agent.AgentControlController;
import org.jarvis.apigateway.agent.AgentRegistry;
import org.jarvis.apigateway.agent.PanicPropagator;
import org.jarvis.common.JarvisCommonAutoConfiguration;
import org.jarvis.common.safety.SystemPanicState;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRITICAL finding #3: {@code POST /api/v1/agent/panic} (and its sibling
 * {@code /panic/clear}, {@code /{agentId}/kill-switch}) had no role check —
 * any authenticated user with a plain "USER" JWT could toggle the system-wide
 * emergency stop. Verifies that a non-admin JWT is now rejected with 403 and
 * an ADMIN JWT is still accepted.
 */
@WebMvcTest(
        controllers = AgentControlController.class,
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
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtil.class, JarvisCommonAutoConfiguration.class, SystemPanicState.class})
@ActiveProfiles("test")
class AgentControlPanicRoleAuthorizationIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private SystemPanicState panicState;

    @Value("${jarvis.jwt.secret}")
    private String jwtSecret;

    @MockBean
    private AgentRegistry agentRegistry;

    @MockBean
    private PanicPropagator panicPropagator;

    private MockMvc mockMvc;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
        panicState.clear("test-setup", System.currentTimeMillis());
    }

    @Test
    void nonAdminJwtIsForbiddenFromEngagingPanic() throws Exception {
        mockMvc.perform(post("/api/v1/agent/panic")
                        .header("Authorization", "Bearer " + buildToken("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"attacker\",\"reason\":\"dos\"}"))
                .andExpect(status().isForbidden());

        verify(panicPropagator, never()).propagate(any(Boolean.class), any(), any());
    }

    @Test
    void adminJwtCanEngagePanic() throws Exception {
        mockMvc.perform(post("/api/v1/agent/panic")
                        .header("Authorization", "Bearer " + buildToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"operator\",\"reason\":\"drill\"}"))
                .andExpect(status().isOk());

        verify(panicPropagator).propagate(true, "operator", "drill");
    }

    private String buildToken(String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", role)
                .claim("type", "access")
                .issuer("jarvis")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
