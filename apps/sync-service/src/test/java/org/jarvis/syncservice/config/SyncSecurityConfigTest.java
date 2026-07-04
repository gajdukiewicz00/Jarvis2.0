package org.jarvis.syncservice.config;

import jakarta.servlet.Filter;
import org.jarvis.syncservice.controller.SyncController;
import org.jarvis.syncservice.service.BlobInboxService;
import org.jarvis.syncservice.service.PairingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real {@link SyncSecurityConfig#syncFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity)}
 * bean (not the WebMvcTest-slice's default deny-everything security auto-config) by
 * manually wiring the actual {@code springSecurityFilterChain} into MockMvc, mirroring
 * the pattern used by planner-service's TaskControllerSecurityTest. Confirms a paired
 * device can call the sync endpoints without CSRF tokens, HTTP Basic, or a session —
 * exactly what the class-level javadoc promises.
 */
@WebMvcTest(controllers = SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SyncSecurityConfig.class)
class SyncSecurityConfigTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @MockBean
    private PairingService pairingService;

    @MockBean
    private BlobInboxService inbox;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Test
    void permitsUnauthenticatedPairingInitWithoutCsrfTokenOrSession() throws Exception {
        when(pairingService.initPairing())
                .thenReturn(new PairingService.InitResponse("nonce", "pub"));

        mockMvc.perform(post("/api/v1/sync/pairing/init"))
                .andExpect(status().isOk());
    }

    @Test
    void permitsUnauthenticatedHealthCheck() throws Exception {
        mockMvc.perform(get("/api/v1/sync/health/inbox"))
                .andExpect(status().isOk());
    }
}
