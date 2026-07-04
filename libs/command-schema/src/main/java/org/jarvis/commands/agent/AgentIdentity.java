package org.jarvis.commands.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Phase 6 — stable identity of a Native Desktop Agent.
 *
 * <p>{@code agentId} is generated once on first run and persisted under
 * {@code ~/.jarvis/agent/identity.json}; it survives restarts so the
 * backend can correlate sessions. {@code hostId} pins it to a particular
 * machine — re-installations on the same host should reuse the same value.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentIdentity {

    private String agentId;
    private String hostId;
    private String hostname;
    private String os;
    private String osVersion;
    private String agentVersion;
    private Instant registeredAt;
}
