package org.jarvis.swarm.web;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.permission.PanicEngagedException;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.run.SwarmNotFoundException;
import org.jarvis.swarm.sandbox.SandboxException;
import org.jarvis.swarm.task.InvalidTransitionException;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmExceptionHandlerTest {

    private final SwarmExceptionHandler handler = new SwarmExceptionHandler();

    @Test
    void sandboxExceptionMapsToBadRequest() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSandbox(new SandboxException("bad path"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("success", false).containsEntry("error", "bad path");
    }

    @Test
    void illegalArgumentMapsToBadRequest() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBadRequest(new IllegalArgumentException("nope"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "nope");
    }

    @Test
    void taskNotFoundMapsToNotFound() {
        ResponseEntity<Map<String, Object>> resp = handler.handleNotFound(new TaskNotFoundException("t1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().get("error")).asString().contains("t1");
    }

    @Test
    void swarmNotFoundMapsToNotFound() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSwarmNotFound(new SwarmNotFoundException("s1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidTransitionMapsToConflict() {
        ResponseEntity<Map<String, Object>> resp = handler.handleTransition(
                new InvalidTransitionException(AgentTaskStatus.COMPLETED, AgentTaskStatus.RUNNING));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void permissionDeniedMapsToForbidden() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDenied(
                new PermissionDeniedException(ToolPermission.RUN_SHELL, "not granted"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void panicEngagedMapsToLocked() {
        ResponseEntity<Map<String, Object>> resp = handler.handlePanic(new PanicEngagedException());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void unauthenticatedMapsToUnauthorized() {
        ResponseEntity<Map<String, Object>> resp = handler.handleUnauth(new UnauthenticatedException());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void swarmDisabledMapsToServiceUnavailable() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDisabled(new SwarmDisabledException());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void rejectedExecutionMapsToServiceUnavailableWithGenericMessage() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSaturated(new RejectedExecutionException("pool full"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("error", "agent queue saturated; retry later");
    }

    @Test
    void genericExceptionMapsToInternalServerErrorWithoutLeakingDetail() {
        ResponseEntity<Map<String, Object>> resp = handler.handleGeneric(new RuntimeException("db password=hunter2"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsEntry("error", "Internal server error");
    }

    @Test
    void nullMessageIsRenderedAsEmptyString() {
        ResponseEntity<Map<String, Object>> resp = handler.handleSandbox(new SandboxException(null));
        assertThat(resp.getBody()).containsEntry("error", "");
    }
}
