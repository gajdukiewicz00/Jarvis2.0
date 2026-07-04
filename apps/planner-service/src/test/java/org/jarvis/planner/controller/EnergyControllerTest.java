package org.jarvis.planner.controller;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.EnergyAwareRanker;
import org.jarvis.planner.service.EnergyStateService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EnergyController.class)
@AutoConfigureMockMvc(addFilters = false)
class EnergyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnergyStateService energyStateService;

    @MockBean
    private EnergyAwareRanker ranker;

    @MockBean
    private TaskRepository taskRepository;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    private Task task(Long id, String title, TaskPriority priority) {
        Task t = new Task();
        t.setId(id);
        t.setTitle(title);
        t.setPriority(priority);
        t.setEstimatedDuration(30);
        return t;
    }

    @Test
    @DisplayName("POST /energy with HIGH acknowledges high-energy message")
    void setEnergyHighReturnsHighAckMessage() throws Exception {
        when(energyStateService.set("user-1", EnergyLevel.HIGH)).thenReturn(EnergyLevel.HIGH);

        mockMvc.perform(post("/api/v1/planner/energy")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energy").value("HIGH"))
                .andExpect(jsonPath("$.message").value("Принято, сэр — вы полны сил. Подберу задачи посложнее."));

        verify(energyStateService).set("user-1", EnergyLevel.HIGH);
    }

    @Test
    @DisplayName("POST /energy with EXHAUSTED acknowledges minimal-mode message")
    void setEnergyExhaustedReturnsExhaustedAckMessage() throws Exception {
        when(energyStateService.set("user-1", EnergyLevel.EXHAUSTED)).thenReturn(EnergyLevel.EXHAUSTED);

        mockMvc.perform(post("/api/v1/planner/energy")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"истощён\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energy").value("EXHAUSTED"))
                .andExpect(jsonPath("$.message").value("Понял, сэр. Режим минимума — только лёгкое и отдых."));
    }

    @Test
    @DisplayName("POST /energy with LOW acknowledges low-energy message")
    void setEnergyLowReturnsLowAckMessage() throws Exception {
        when(energyStateService.set("user-1", EnergyLevel.LOW)).thenReturn(EnergyLevel.LOW);

        mockMvc.perform(post("/api/v1/planner/energy")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energy").value("LOW"))
                .andExpect(jsonPath("$.message").value("Понял, сэр — энергии немного. Начнём с лёгкого."));
    }

    @Test
    @DisplayName("POST /energy with empty body defaults to NORMAL")
    void setEnergyWithEmptyBodyDefaultsToNormal() throws Exception {
        when(energyStateService.set("user-1", EnergyLevel.NORMAL)).thenReturn(EnergyLevel.NORMAL);

        mockMvc.perform(post("/api/v1/planner/energy")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energy").value("NORMAL"))
                .andExpect(jsonPath("$.message").value("Принято, сэр. Сбалансированный план."));
    }

    @Test
    @DisplayName("GET /energy returns the current stored level")
    void getEnergyReturnsStoredLevel() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.LOW);

        mockMvc.perform(get("/api/v1/planner/energy").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energy").value("LOW"));

        verify(energyStateService).get("user-1");
    }

    @Test
    @DisplayName("GET /next-task with no ranked tasks and EXHAUSTED energy suggests rest")
    void nextTaskWithNoTasksAndExhaustedEnergySuggestsRest() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(eq(List.of()), eq(EnergyLevel.EXHAUSTED), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/next-task").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasTask").value(false))
                .andExpect(jsonPath("$.message").value("Задач нет, сэр. Отдыхайте."));
    }

    @Test
    @DisplayName("GET /next-task with no ranked tasks and NORMAL energy reports nothing to do")
    void nextTaskWithNoTasksAndNormalEnergyReportsNothing() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(eq(List.of()), eq(EnergyLevel.NORMAL), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/next-task").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasTask").value(false))
                .andExpect(jsonPath("$.message").value("Никаких задач, сэр."));
    }

    @Test
    @DisplayName("GET /next-task?force=true forwards the force flag and returns top ranked task")
    void nextTaskWithForceReturnsTopRankedTask() throws Exception {
        Task top = task(5L, "Deploy release", TaskPriority.HIGH);
        List<Task> active = List.of(top);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.HIGH);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.HIGH, true)).thenReturn(List.of(top));
        when(ranker.explain(top, EnergyLevel.HIGH)).thenReturn("explanation");

        mockMvc.perform(get("/api/v1/planner/next-task")
                        .principal(authenticatedUser("user-1"))
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasTask").value(true))
                .andExpect(jsonPath("$.taskId").value(5))
                .andExpect(jsonPath("$.title").value("Deploy release"))
                .andExpect(jsonPath("$.explanation").value("explanation"))
                .andExpect(jsonPath("$.openTasks").value(1))
                .andExpect(jsonPath("$.force").value(true));
    }

    @Test
    @DisplayName("GET /plan-by-energy with no tasks reports empty explanation")
    void planByEnergyWithNoTasksReportsEmptyExplanation() throws Exception {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(eq(List.of()), eq(EnergyLevel.NORMAL), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/plan-by-energy").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").isEmpty())
                .andExpect(jsonPath("$.explanation").value("Задач нет, сэр."));
    }

    @Test
    @DisplayName("GET /plan-by-energy with ranked tasks returns the ordered plan")
    void planByEnergyWithRankedTasksReturnsOrderedPlan() throws Exception {
        Task top = task(9L, "Write report", TaskPriority.MEDIUM);
        List<Task> active = List.of(top);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.LOW);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.LOW, false)).thenReturn(List.of(top));
        when(ranker.explain(top, EnergyLevel.LOW)).thenReturn("take it easy");

        mockMvc.perform(get("/api/v1/planner/plan-by-energy").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskId").value(9))
                .andExpect(jsonPath("$.tasks[0].title").value("Write report"))
                .andExpect(jsonPath("$.explanation").value("take it easy"));
    }
}
