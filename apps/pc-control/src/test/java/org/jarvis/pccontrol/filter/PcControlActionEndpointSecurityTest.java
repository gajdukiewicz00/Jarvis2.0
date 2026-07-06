package org.jarvis.pccontrol.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jarvis.pccontrol.controller.PcControlController;
import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.service.PcActionExecutionService;
import org.jarvis.pccontrol.service.PcScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Regression test for the CRITICAL finding: the admin-role gate in
 * {@link TokenValidationFilter} previously only matched dead paths
 * ("/api/v1/pc/shutdown", "/api/v1/pc/restart") that no controller maps to.
 * The real host-action dispatch endpoint, POST /api/v1/pc/action
 * ({@link PcControlController#executeAction}), was left completely
 * unenforced, so any authenticated non-admin token could execute host
 * actions (SYSTEM_COMMAND, LOCK_SCREEN, SCREENSHOT, HOTKEY, etc).
 *
 * <p>This wires the real filter together with the real controller mapping
 * (rather than asserting against a hand-picked URI string) so the test
 * fails if the filter's admin-only path list ever drifts from the actual
 * controller route again.
 */
@ExtendWith(MockitoExtension.class)
class PcControlActionEndpointSecurityTest {

    private static final String ACTION_ENDPOINT = "/api/v1/pc/action";

    @Mock
    private PcActionExecutionService executionService;

    @Mock
    private PcScenarioRegistry scenarioRegistry;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        PcControlController controller = new PcControlController(executionService, scenarioRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilter(new TokenValidationFilter())
                .build();
    }

    @Test
    void nonAdminTokenIsRejectedOnRealActionEndpoint() throws Exception {
        PcActionRequest request = new PcActionRequest("LOCK_SCREEN", Map.of());

        mockMvc.perform(post(ACTION_ENDPOINT)
                        .header("X-User-Id", "user-1")
                        .header("X-User-Roles", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(executionService, never()).execute(any());
    }

    @Test
    void missingTokenIsRejectedOnRealActionEndpoint() throws Exception {
        PcActionRequest request = new PcActionRequest("LOCK_SCREEN", Map.of());

        mockMvc.perform(post(ACTION_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verify(executionService, never()).execute(any());
    }

    @Test
    void adminTokenIsAllowedOnRealActionEndpoint() throws Exception {
        PcActionRequest request = new PcActionRequest("LOCK_SCREEN", Map.of());
        when(executionService.execute(request)).thenReturn(new PcActionResult(
                true,
                "LOCK_SCREEN",
                PcActionExecutionStatus.SUCCESS,
                "Screen locked",
                null,
                Map.of(),
                List.of(),
                Instant.now()));

        mockMvc.perform(post(ACTION_ENDPOINT)
                        .header("X-User-Id", "admin-1")
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(executionService).execute(request);
    }
}
