package org.jarvis.apigateway.agent;

import org.jarvis.commands.agent.AgentHeartbeat;
import org.jarvis.commands.agent.AgentIdentity;
import org.jarvis.common.safety.SystemPanicState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the EPIC 3 panic surface plus the registration / heartbeat /
 * kill-switch routes of {@link AgentControlController}. Uses a real
 * {@link SystemPanicState} (cheap, deterministic) and mocks for the registry
 * and the best-effort orchestrator propagator.
 */
@ExtendWith(MockitoExtension.class)
class AgentControlControllerTest {

    @Mock
    private AgentRegistry registry;

    @Mock
    private PanicPropagator panicPropagator;

    private SystemPanicState panicState;
    private AgentControlController controller;

    @BeforeEach
    void setUp() {
        panicState = new SystemPanicState();
        controller = new AgentControlController(registry, panicState, panicPropagator);
    }

    @Test
    void engagePanicWithBodyUsesProvidedActorAndReason() {
        ResponseEntity<Map<String, Object>> response =
                controller.engagePanic(new AgentControlController.PanicRequest("operator", "drill"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("engaged", true).containsEntry("reason", "drill");
        assertThat(panicState.isEngaged()).isTrue();
        verify(panicPropagator).propagate(true, "operator", "drill");
    }

    @Test
    void engagePanicWithNullBodyUsesDefaults() {
        ResponseEntity<Map<String, Object>> response = controller.engagePanic(null);

        assertThat(response.getBody()).containsEntry("engaged", true).containsEntry("reason", "panic engaged via REST");
        verify(panicPropagator).propagate(true, "api", "panic engaged via REST");
    }

    @Test
    void engagePanicWithPartialBodyFillsMissingFields() {
        controller.engagePanic(new AgentControlController.PanicRequest(null, null));

        verify(panicPropagator).propagate(true, "api", "panic engaged via REST");
    }

    @Test
    void clearPanicWithBodyUsesProvidedActor() {
        panicState.engage("operator", "drill", 1L);

        ResponseEntity<Map<String, Object>> response =
                controller.clearPanic(new AgentControlController.PanicRequest("operator", "ignored-reason"));

        assertThat(response.getBody()).containsEntry("engaged", false);
        assertThat(panicState.isEngaged()).isFalse();
        verify(panicPropagator).propagate(false, "operator", "cleared");
    }

    @Test
    void clearPanicWithNullBodyUsesDefaultActor() {
        ResponseEntity<Map<String, Object>> response = controller.clearPanic(null);

        assertThat(response.getBody()).containsEntry("engaged", false);
        verify(panicPropagator).propagate(false, "api", "cleared");
    }

    @Test
    void panicStatusReturnsCurrentSnapshot() {
        panicState.engage("operator", "drill", 42L);

        ResponseEntity<Map<String, Object>> response = controller.panicStatus();

        assertThat(response.getBody()).containsEntry("engaged", true).containsEntry("actor", "operator");
        verifyNoInteractions(panicPropagator);
    }

    @Test
    void registerReturnsOkOnSuccess() {
        AgentIdentity identity = identity("agent-1");
        when(registry.register(identity)).thenReturn(identity);

        ResponseEntity<AgentIdentity> response = controller.register(identity);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(identity);
    }

    @Test
    void registerReturnsBadRequestOnIllegalArgument() {
        AgentIdentity bad = AgentIdentity.builder().build();
        when(registry.register(bad)).thenThrow(new IllegalArgumentException("AgentIdentity.agentId is required"));

        ResponseEntity<AgentIdentity> response = controller.register(bad);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void heartbeatAcceptedWhenRegistryAccepts() {
        AgentHeartbeat heartbeat = AgentHeartbeat.builder().identity(identity("agent-2")).build();
        when(registry.recordHeartbeat(heartbeat)).thenReturn(true);

        ResponseEntity<Void> response = controller.heartbeat(heartbeat);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void heartbeatBadRequestWhenRegistryRejects() {
        AgentHeartbeat heartbeat = AgentHeartbeat.builder().build();
        when(registry.recordHeartbeat(heartbeat)).thenReturn(false);

        ResponseEntity<Void> response = controller.heartbeat(heartbeat);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void killSwitchEngageAcceptedWhenSuccessfulAndFillsDefaults() {
        when(registry.engageKillSwitch("agent-3", "api", "engaged via REST")).thenReturn(true);

        ResponseEntity<Void> response = controller.killSwitch("agent-3",
                new AgentControlController.KillSwitchRequest(true, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(registry).engageKillSwitch("agent-3", "api", "engaged via REST");
        verify(registry, never()).disengageKillSwitch(eq("agent-3"), eq("api"));
    }

    @Test
    void killSwitchEngageNotFoundWhenAgentMissing() {
        when(registry.engageKillSwitch("ghost", "operator", "stop")).thenReturn(false);

        ResponseEntity<Void> response = controller.killSwitch("ghost",
                new AgentControlController.KillSwitchRequest(true, "operator", "stop"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void killSwitchDisengageAcceptedWhenSuccessfulAndFillsDefaultActor() {
        when(registry.disengageKillSwitch("agent-4", "api")).thenReturn(true);

        ResponseEntity<Void> response = controller.killSwitch("agent-4",
                new AgentControlController.KillSwitchRequest(false, null, "ignored"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(registry).disengageKillSwitch("agent-4", "api");
    }

    @Test
    void killSwitchDisengageNotFoundWhenAgentMissing() {
        when(registry.disengageKillSwitch("ghost", "operator")).thenReturn(false);

        ResponseEntity<Void> response = controller.killSwitch("ghost",
                new AgentControlController.KillSwitchRequest(false, "operator", null));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getReturnsEntryWhenPresent() {
        AgentIdentity identity = identity("agent-5");
        AgentRegistry.Entry entry = new AgentRegistry.Entry(identity);
        when(registry.get("agent-5")).thenReturn(Optional.of(entry));

        ResponseEntity<AgentRegistry.Entry> response = controller.get("agent-5");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(entry);
    }

    @Test
    void getReturnsNotFoundWhenAbsent() {
        when(registry.get("ghost")).thenReturn(Optional.empty());

        ResponseEntity<AgentRegistry.Entry> response = controller.get("ghost");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void listReturnsAllRegistryEntries() {
        AgentRegistry.Entry entry = new AgentRegistry.Entry(identity("agent-6"));
        when(registry.list()).thenReturn(List.of(entry));

        assertThat(controller.list()).containsExactly(entry);
    }

    private AgentIdentity identity(String id) {
        return AgentIdentity.builder()
                .agentId(id)
                .hostId("host-" + id)
                .hostname("test-host")
                .os("linux")
                .osVersion("6.x")
                .agentVersion("test")
                .registeredAt(Instant.now())
                .build();
    }
}
