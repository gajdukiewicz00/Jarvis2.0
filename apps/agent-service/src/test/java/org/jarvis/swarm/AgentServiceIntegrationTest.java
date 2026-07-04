package org.jarvis.swarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.common.safety.SystemPanicState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full agent-service context (proves the service starts) and exercises health,
 * auth, a CODER dryRun task, RUN_SHELL permission denial, panic blocking, and a swarm
 * combined report over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentServiceIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SystemPanicState panic;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unauthenticatedTaskCreationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/agents/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CODER\",\"goal\":\"x\",\"dryRun\":true}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser
    void roleCatalogIsExposed() throws Exception {
        mockMvc.perform(get("/api/v1/agents/roles").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @WithMockUser
    void coderDryRunTaskCompletesWithProposedActions() throws Exception {
        JsonNode created = create("{\"role\":\"CODER\",\"goal\":\"add a parser\",\"dryRun\":true}");
        JsonNode done = awaitTask(created.get("taskId").asText());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("dryRun").asBoolean()).isTrue();
        assertThat(done.get("result").get("proposedActions").size()).isGreaterThan(0);
    }

    @Test
    @WithMockUser
    void runShellTaskIsRejectedByPolicy() throws Exception {
        JsonNode created = create(
                "{\"role\":\"TESTER\",\"goal\":\"run: echo hi\",\"permissions\":[\"RUN_SHELL\"],\"dryRun\":false}");
        JsonNode done = awaitTask(created.get("taskId").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("errorMessage").asText()).contains("RUN_SHELL");
    }

    @Test
    @WithMockUser
    void panicBlocksNewTasks() throws Exception {
        try {
            panic.engage("test", "integration drill", 1L);
            JsonNode created = create("{\"role\":\"DOCS\",\"goal\":\"doc it\",\"dryRun\":true}");
            JsonNode done = awaitTask(created.get("taskId").asText());
            assertThat(done.get("status").asText()).isEqualTo("FAILED");
            assertThat(done.get("errorMessage").asText()).isEqualTo("panic_engaged");
        } finally {
            panic.clear("test", 2L);
        }
    }

    @Test
    @WithMockUser
    void swarmDryRunProducesCombinedReport() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agents/swarm")
                        .header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"build, test and document\",\"roles\":[\"CODER\",\"TESTER\",\"DOCS\"],"
                                + "\"dryRun\":true,\"awaitCompletion\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode report = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(report.get("complete").asBoolean()).isTrue();
        assertThat(report.get("perRole").size()).isEqualTo(3);
        assertThat(report.get("failedRoles").size()).isEqualTo(0);
    }

    // --- helpers ---

    private JsonNode create(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agents/tasks")
                        .header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode awaitTask(String id) throws Exception {
        for (int i = 0; i < 150; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/agents/tasks/" + id).header("X-User-Id", "u1"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode task = mapper.readTree(result.getResponse().getContentAsString());
            String status = task.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED")) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not finish in time");
    }
}
