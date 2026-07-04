package org.jarvis.apigateway.agent;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.agent.AgentHeartbeat;
import org.jarvis.commands.agent.AgentIdentity;
import org.jarvis.commands.agent.AgentStatus;
import org.jarvis.commands.agent.KillSwitchState;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 — in-memory registry of Native Desktop Agents that have called
 * {@code POST /api/v1/agent/register} or {@code .../heartbeat}.
 *
 * <p>Pass 1 keeps the table in memory only. Phase 8 will project agent
 * lifecycle events into Postgres and Kafka so the registry can be rebuilt
 * after an api-gateway restart.</p>
 *
 * <p>An agent that has not heart-beaten within {@code staleThreshold}
 * (default 60s) is automatically flipped to {@link AgentStatus#OFFLINE}
 * by a scheduled sweeper.</p>
 */
@Slf4j
@Component
public class AgentRegistry {

    private final Map<String, Entry> agents = new ConcurrentHashMap<>();
    private final ObjectProvider<AuditPublisher> auditProvider;

    @Value("${jarvis.agent.stale-threshold-seconds:60}")
    private long staleThresholdSeconds;

    public AgentRegistry(ObjectProvider<AuditPublisher> auditProvider) {
        this.auditProvider = auditProvider;
    }

    public AgentIdentity register(AgentIdentity identity) {
        if (identity == null || identity.getAgentId() == null || identity.getAgentId().isBlank()) {
            throw new IllegalArgumentException("AgentIdentity.agentId is required");
        }
        Entry entry = agents.computeIfAbsent(identity.getAgentId(), id -> new Entry(identity));
        entry.identity = identity;
        entry.status = AgentStatus.READY;
        entry.lastSeen = Instant.now();
        log.info("AGENT REGISTERED agent={} host={} os={}",
                identity.getAgentId(), identity.getHostname(), identity.getOs());
        emitAudit(AuditEventType.AGENT_REGISTERED, identity.getAgentId(), null,
                Map.of(
                        "hostname", identity.getHostname() == null ? "" : identity.getHostname(),
                        "os", identity.getOs() == null ? "" : identity.getOs(),
                        "agentVersion", identity.getAgentVersion() == null ? "" : identity.getAgentVersion()
                ));
        return identity;
    }

    public boolean recordHeartbeat(AgentHeartbeat heartbeat) {
        if (heartbeat == null || heartbeat.getIdentity() == null
                || heartbeat.getIdentity().getAgentId() == null) {
            return false;
        }
        String agentId = heartbeat.getIdentity().getAgentId();
        Entry entry = agents.computeIfAbsent(agentId, id -> new Entry(heartbeat.getIdentity()));
        entry.identity = heartbeat.getIdentity();
        entry.status = heartbeat.getStatus() == null ? AgentStatus.READY : heartbeat.getStatus();
        entry.killSwitch = heartbeat.getKillSwitch();
        entry.lastSeen = heartbeat.getSentAt() == null ? Instant.now() : heartbeat.getSentAt();
        entry.metadata = heartbeat.getMetadata();
        log.debug("AGENT HEARTBEAT agent={} status={} killSwitch={}",
                agentId, entry.status, entry.killSwitch == null ? "n/a" : entry.killSwitch.isEngaged());
        return true;
    }

    public Optional<Entry> get(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public Collection<Entry> list() {
        return List.copyOf(agents.values()).stream()
                .sorted(Comparator.comparing((Entry e) -> e.identity.getAgentId()))
                .toList();
    }

    public boolean engageKillSwitch(String agentId, String engagedBy, String reason) {
        Entry e = agents.get(agentId);
        if (e == null) {
            return false;
        }
        e.killSwitch = KillSwitchState.engaged(engagedBy, reason);
        e.status = AgentStatus.KILL_SWITCH;
        log.warn("KILL SWITCH ENGAGED for agent={} by={} reason='{}'", agentId, engagedBy, reason);
        emitAudit(AuditEventType.KILL_SWITCH_ENGAGED, agentId, null,
                Map.of("engagedBy", engagedBy == null ? "" : engagedBy,
                       "reason", reason == null ? "" : reason));
        return true;
    }

    public boolean disengageKillSwitch(String agentId, String engagedBy) {
        Entry e = agents.get(agentId);
        if (e == null) {
            return false;
        }
        e.killSwitch = KillSwitchState.disengaged();
        e.status = AgentStatus.READY;
        log.warn("KILL SWITCH DISENGAGED for agent={} by={}", agentId, engagedBy);
        emitAudit(AuditEventType.KILL_SWITCH_DISENGAGED, agentId, null,
                Map.of("disengagedBy", engagedBy == null ? "" : engagedBy));
        return true;
    }

    private void emitAudit(AuditEventType type, String agentId, String userId,
                           Map<String, Object> payload) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) return;
        publisher.audit(type, null, agentId, userId, null, payload);
    }

    @Scheduled(fixedDelayString = "${jarvis.agent.stale-sweep-ms:30000}")
    public void sweepStale() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(staleThresholdSeconds));
        for (Entry e : agents.values()) {
            if (e.status == AgentStatus.OFFLINE) {
                continue;
            }
            if (e.lastSeen != null && e.lastSeen.isBefore(cutoff)) {
                e.status = AgentStatus.OFFLINE;
                log.warn("AGENT OFFLINE agent={} lastSeen={} (older than {}s)",
                        e.identity.getAgentId(), e.lastSeen, staleThresholdSeconds);
            }
        }
    }

    public static final class Entry {
        public AgentIdentity identity;
        public AgentStatus status = AgentStatus.BOOTING;
        public Instant lastSeen;
        public KillSwitchState killSwitch;
        public Map<String, Object> metadata;

        public Entry(AgentIdentity identity) {
            this.identity = identity;
            this.lastSeen = Instant.now();
        }
    }
}
