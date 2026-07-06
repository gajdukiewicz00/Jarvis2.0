package org.jarvis.planner.controller;

import org.jarvis.planner.service.PlanAdjustmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlanAdjustmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlanAdjustmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlanAdjustmentService planAdjustmentService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("GET /plan/adjusted returns the wellness-adjusted plan for the authenticated user")
    void adjustedPlanReturnsServiceResult() throws Exception {
        when(planAdjustmentService.getAdjustedPlan("user-1", false)).thenReturn(Map.of(
                "mode", "RECOVERY",
                "energy", "EXHAUSTED",
                "tasks", List.of(),
                "explanation", "Режим восстановления, сэр."));

        mockMvc.perform(get("/api/v1/planner/plan/adjusted").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RECOVERY"))
                .andExpect(jsonPath("$.energy").value("EXHAUSTED"))
                .andExpect(jsonPath("$.explanation").value("Режим восстановления, сэр."));
    }

    @Test
    @DisplayName("GET /plan/adjusted forwards the force query parameter")
    void adjustedPlanForwardsForceParameter() throws Exception {
        when(planAdjustmentService.getAdjustedPlan("user-1", true)).thenReturn(Map.of(
                "mode", "DEEP_WORK", "energy", "HIGH", "tasks", List.of(), "explanation", "..."));

        mockMvc.perform(get("/api/v1/planner/plan/adjusted?force=true").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEEP_WORK"));
    }
}
