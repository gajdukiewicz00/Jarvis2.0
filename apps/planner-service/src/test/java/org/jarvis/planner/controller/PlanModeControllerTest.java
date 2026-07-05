package org.jarvis.planner.controller;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.EnergyAwareRanker;
import org.jarvis.planner.service.EnergyStateService;
import org.jarvis.planner.service.PlanModeSelectionService;
import org.jarvis.planner.service.PlanModeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockBean
    private PlanModeSelectionService planModeSelectionService;

    @MockBean
    private EnergyAwareRanker ranker;

    @MockBean
    private TaskRepository taskRepository;

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

    @Test
    @DisplayName("POST /plan/mode persists the requested plan mode for the authenticated user")
    void setModePersistsRequestedMode() throws Exception {
        when(planModeSelectionService.setMode("user-1", PlanMode.DEEP_WORK)).thenReturn(PlanMode.DEEP_WORK);

        mockMvc.perform(post("/api/v1/planner/plan/mode")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"deep_work\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEEP_WORK"));
    }

    @Test
    @DisplayName("GET /plan/mode returns the user's persisted mode selection")
    void getModeReturnsPersistedMode() throws Exception {
        when(planModeSelectionService.getMode("user-1")).thenReturn(PlanMode.STUDY);

        mockMvc.perform(get("/api/v1/planner/plan/mode").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("STUDY"));
    }

    @Test
    @DisplayName("GET /plan/by-mode ranks tasks using the persisted mode plus current energy")
    void planByModeRanksUsingPersistedModeAndCurrentEnergy() throws Exception {
        Task task = new Task();
        task.setId(7L);
        task.setTitle("Read chapter 3");

        when(planModeSelectionService.getMode("user-1")).thenReturn(PlanMode.STUDY);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(task));
        when(ranker.rank(List.of(task), EnergyLevel.NORMAL, false, PlanMode.STUDY)).thenReturn(List.of(task));
        when(ranker.deadlineLabel(any())).thenReturn("NONE");

        mockMvc.perform(get("/api/v1/planner/plan/by-mode").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("STUDY"))
                .andExpect(jsonPath("$.energy").value("NORMAL"))
                .andExpect(jsonPath("$.tasks[0].taskId").value(7))
                .andExpect(jsonPath("$.tasks[0].title").value("Read chapter 3"));
    }
}
