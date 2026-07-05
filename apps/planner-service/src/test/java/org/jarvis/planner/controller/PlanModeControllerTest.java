package org.jarvis.planner.controller;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.service.EnergyStateService;
import org.jarvis.planner.service.PlanModeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlanModeController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlanModeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlanModeService planModeService;

    @MockBean
    private EnergyStateService energyStateService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("GET /plan/minimum-viable-day forwards the user's current energy to the service")
    void minimumViableDayForwardsCurrentEnergy() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.LOW);
        when(planModeService.minimumViableDay("user-1", EnergyLevel.LOW)).thenReturn(Map.of(
                "mode", "MINIMUM_VIABLE_DAY", "taskCount", 2));

        mockMvc.perform(get("/api/v1/planner/plan/minimum-viable-day").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MINIMUM_VIABLE_DAY"))
                .andExpect(jsonPath("$.taskCount").value(2));
    }

    @Test
    @DisplayName("GET /plan/deep-work-block forwards the user's current energy to the service")
    void deepWorkBlockForwardsCurrentEnergy() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.HIGH);
        when(planModeService.deepWorkBlock("user-1", EnergyLevel.HIGH)).thenReturn(Map.of(
                "mode", "DEEP_WORK_BLOCK", "hasBlock", true, "blockMinutes", 90));

        mockMvc.perform(get("/api/v1/planner/plan/deep-work-block").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasBlock").value(true))
                .andExpect(jsonPath("$.blockMinutes").value(90));
    }
}
