package org.jarvis.memory.controller;

import jakarta.servlet.Filter;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.memory.config.SecurityConfig;
import org.jarvis.memory.service.MemoryDependencyStatusService;
import org.jarvis.memory.service.MemoryService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Exercises the real {@link SecurityConfig} filter chain (not just the
 * public-endpoint list) to confirm the Prometheus scrape fix: unauthenticated
 * scraping of {@code /actuator/prometheus} is permitted, while a real
 * {@code /api/v1/memory/**} API path is still rejected without a valid
 * service token — the security posture this change must not weaken.
 */
@WebMvcTest(controllers = MemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class MemoryControllerSecurityTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @MockBean
    private MemoryService memoryService;

    @MockBean
    private MemoryDependencyStatusService memoryDependencyStatusService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void actuatorPrometheusIsPermittedWithoutAuthentication() throws Exception {
        // No handler is registered in this controller-scoped slice, so a
        // permitAll match falls through security to a 404 from the
        // dispatcher. A still-authenticated path would instead be rejected
        // by the security filter chain itself with 401/403 before ever
        // reaching the dispatcher.
        int status = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void memoryApiSearchWithoutValidJwtReturnsUnauthorizedOrForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/memory/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"project notes\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value()));
    }
}
