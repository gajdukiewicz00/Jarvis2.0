package org.jarvis.commands.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 — periodic heartbeat the agent posts to
 * {@code POST /api/v1/agent/heartbeat}.
 *
 * <p>Carries the latest snapshot of identity, capabilities, kill-switch
 * state, and a free-form metadata map (queue depths, model status, etc.).
 * Agents with no recent heartbeat are flipped to {@link AgentStatus#OFFLINE}
 * by the backend registry.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentHeartbeat {

    private AgentIdentity identity;
    private AgentStatus status;
    private Set<AgentCapability> capabilities;
    private KillSwitchState killSwitch;
    private Instant sentAt;

    /** Lightweight key/value extras: live-feed depth, queue depths, model health, ... */
    private Map<String, Object> metadata;
}
