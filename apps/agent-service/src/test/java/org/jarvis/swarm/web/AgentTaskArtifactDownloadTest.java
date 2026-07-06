package org.jarvis.swarm.web;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.ArtifactNotFoundException;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct (non-MockMvc) coverage of the agent-task artifact download endpoints: the
 * CODER DIFF.patch download and the rendered combined report, mirroring
 * {@code MediaJobControllerArtifactDownloadTest}'s pattern for media-service.
 */
class AgentTaskArtifactDownloadTest {

    @TempDir
    Path tmp;

    private SwarmTestFactory.Engine engine;
    private AgentTaskController controller;

    @BeforeEach
    void setUp() {
        engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        controller = new AgentTaskController(engine.taskService(), new SwarmFeatureGate(engine.props()), engine.sandbox());
    }

    private MockHttpServletRequest requestAs(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId);
        return request;
    }

    @Test
    void downloadsDiffPatchForACompletedCoderTask() throws Exception {
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null);
        AgentTask done = engine.taskService().getTask(created.taskId(), "u1");
        Path diffOnDisk = Path.of(done.sandboxPath()).resolve("DIFF.patch");

        ResponseEntity<Resource> response = controller.downloadDiff(created.taskId(), requestAs("u1"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("diff.patch");
        assertThat(response.getBody()).isNotNull();
        assertThat(readAll(response)).isEqualTo(Files.readAllBytes(diffOnDisk));
    }

    @Test
    void diffDownloadFailsWithArtifactNotFoundForADryRunTaskThatWroteNothing() {
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), true, null, null);

        assertThatThrownBy(() -> controller.downloadDiff(created.taskId(), requestAs("u1")))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void diffDownloadOfATaskNotOwnedByCallerIsRejected() {
        AgentTask created = engine.taskService().submit("owner", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null);

        assertThatThrownBy(() -> controller.downloadDiff(created.taskId(), requestAs("intruder")))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void downloadsACombinedReportWithSummaryRisksAndArtifacts() {
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null);

        ResponseEntity<Resource> response = controller.downloadReport(created.taskId(), requestAs("u1"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo(created.taskId() + "-report.md");
        String body = new String(readAll(response), StandardCharsets.UTF_8);
        assertThat(body).contains("# Agent Task Report");
        assertThat(body).contains("add a caching layer");
        assertThat(body).contains("DIFF.patch");
    }

    private byte[] readAll(ResponseEntity<Resource> response) {
        try {
            return response.getBody().getContentAsByteArray();
        } catch (Exception e) {
            throw new AssertionError("could not read response body", e);
        }
    }
}
