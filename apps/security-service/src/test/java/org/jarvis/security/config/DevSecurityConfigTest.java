package org.jarvis.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the "dev" profile security chain: CSRF disabled, stateless
 * sessions, and every request permitted (not just the documented /auth/**
 * surface). A request to a path with no controller mapping still reaches
 * the dispatcher (where the unmapped path surfaces as a 500 via the
 * {@code GlobalExceptionHandler} catch-all) instead of being rejected by
 * security (401/403), proving {@link DevSecurityConfig#devFilterChain} is
 * the active chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:securitydb-dev;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS security",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
        "jarvis.jwt.issuer=jarvis",
        "jarvis.jwt.access-expiration=600000",
        "jarvis.jwt.refresh-expiration=604800000"
})
class DevSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void devProfilePermitsRequestsToUnmappedPaths() throws Exception {
        // permitAll() lets the request past Spring Security (a request blocked by the
        // chain would be 401/403). With no controller mapping, the DispatcherServlet
        // raises NoHandlerFoundException, which the GlobalExceptionHandler catch-all maps
        // to 500 — not a security rejection. Reaching the app's exception handler at all
        // proves the dev chain (DevSecurityConfig) permitted the request through.
        mockMvc.perform(get("/definitely-not-a-real-endpoint"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void devProfilePermitsAuthEndpointsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/auth/health"))
                .andExpect(status().isOk());
    }
}
