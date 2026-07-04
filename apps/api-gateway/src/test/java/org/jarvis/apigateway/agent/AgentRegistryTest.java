package org.jarvis.apigateway.agent;

import org.jarvis.commands.agent.AgentHeartbeat;
import org.jarvis.commands.agent.AgentIdentity;
import org.jarvis.commands.agent.AgentStatus;
import org.jarvis.commands.agent.KillSwitchState;
import org.jarvis.common.eventbus.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRegistryTest {

    private AgentRegistry registry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<AuditPublisher> noopProvider = mock(ObjectProvider.class);
        when(noopProvider.getIfAvailable()).thenReturn(null);
        registry = new AgentRegistry(noopProvider);
        ReflectionTestUtils.setField(registry, "staleThresholdSeconds", 60L);
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

    @Test
    void registerStoresIdentityAndMarksReady() {
        AgentIdentity id = identity("agent-1");
        registry.register(id);
        assertThat(registry.get("agent-1")).isPresent();
        assertThat(registry.get("agent-1").get().status).isEqualTo(AgentStatus.READY);
    }

    @Test
    void registerRejectsBlankAgentId() {
        AgentIdentity bad = AgentIdentity.builder().build();
        assertThatThrownBy(() -> registry.register(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    void heartbeatUpdatesStatus() {
        registry.register(identity("agent-2"));
        AgentHeartbeat hb = AgentHeartbeat.builder()
                .identity(identity("agent-2"))
                .status(AgentStatus.DEGRADED)
                .killSwitch(KillSwitchState.disengaged())
                .sentAt(Instant.now())
                .build();
        assertThat(registry.recordHeartbeat(hb)).isTrue();
        assertThat(registry.get("agent-2").get().status).isEqualTo(AgentStatus.DEGRADED);
    }

    @Test
    void heartbeatFromUnknownAgentRegistersOnTheFly() {
        AgentHeartbeat hb = AgentHeartbeat.builder()
                .identity(identity("late-arrival"))
                .status(AgentStatus.READY)
                .sentAt(Instant.now())
                .build();
        registry.recordHeartbeat(hb);
        assertThat(registry.get("late-arrival")).isPresent();
    }

    @Test
    void engageKillSwitchFlipsStatus() {
        registry.register(identity("agent-3"));
        boolean ok = registry.engageKillSwitch("agent-3", "operator", "stop everything");
        assertThat(ok).isTrue();
        AgentRegistry.Entry e = registry.get("agent-3").get();
        assertThat(e.status).isEqualTo(AgentStatus.KILL_SWITCH);
        assertThat(e.killSwitch.isEngaged()).isTrue();
        assertThat(e.killSwitch.getEngagedBy()).isEqualTo("operator");
    }

    @Test
    void disengageRestoresReady() {
        registry.register(identity("agent-4"));
        registry.engageKillSwitch("agent-4", "operator", "x");
        registry.disengageKillSwitch("agent-4", "operator");
        AgentRegistry.Entry e = registry.get("agent-4").get();
        assertThat(e.status).isEqualTo(AgentStatus.READY);
        assertThat(e.killSwitch.isEngaged()).isFalse();
    }

    @Test
    void unknownAgentKillSwitchReturnsFalse() {
        assertThat(registry.engageKillSwitch("ghost", "x", "y")).isFalse();
        assertThat(registry.disengageKillSwitch("ghost", "x")).isFalse();
    }

    @Test
    void sweepFlipsStaleAgentsToOffline() {
        AgentIdentity id = identity("agent-5");
        registry.register(id);
        AgentRegistry.Entry e = registry.get("agent-5").get();
        e.lastSeen = Instant.now().minusSeconds(120); // older than 60s threshold
        registry.sweepStale();
        assertThat(e.status).isEqualTo(AgentStatus.OFFLINE);
    }
}
