package org.jarvis.orchestrator.command.confirmation;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.ConfirmationResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5 — registry of commands awaiting an owner confirmation.
 *
 * <p>Keyed by {@code commandId}. Each entry holds the original
 * {@link CommandEnvelope}, the caller's future (completed once a
 * decision arrives), and a hard deadline used by the scheduled sweeper
 * to enforce TIMEOUT.</p>
 */
@Slf4j
@Component
public class PendingConfirmationRegistry {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ObjectProvider<ConfirmationAuditor> auditorProvider;

    @Value("${jarvis.command.pending.sweep-interval-ms:1000}")
    private long sweepIntervalMs;

    public PendingConfirmationRegistry(ObjectProvider<ConfirmationAuditor> auditorProvider) {
        this.auditorProvider = auditorProvider;
    }

    public CompletableFuture<CommandResult> register(CommandEnvelope envelope) {
        Pending existing = pending.get(envelope.getCommandId());
        if (existing != null) {
            log.warn("duplicate confirmation registration for {} — returning existing future",
                    envelope.getCommandId());
            return existing.future;
        }
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        pending.put(envelope.getCommandId(), new Pending(envelope, future));
        log.info("[{}] confirmation pending: intent={} risk={} expiresAt={}",
                envelope.getCommandId(), envelope.getIntent(),
                envelope.getRiskLevel(), envelope.getExpiresAt());
        return future;
    }

    public Optional<CommandEnvelope> lookup(String commandId) {
        Pending p = pending.get(commandId);
        return p == null ? Optional.empty() : Optional.of(p.envelope);
    }

    public CompletableFuture<CommandResult> takeFuture(String commandId) {
        Pending p = pending.remove(commandId);
        return p == null ? null : p.future;
    }

    public boolean isPending(String commandId) {
        return pending.containsKey(commandId);
    }

    public int size() {
        return pending.size();
    }

    @Scheduled(fixedDelayString = "${jarvis.command.pending.sweep-interval-ms:1000}")
    public void sweepExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        int swept = 0;
        while (it.hasNext()) {
            Map.Entry<String, Pending> entry = it.next();
            CommandEnvelope env = entry.getValue().envelope;
            if (env.isExpired(now)) {
                it.remove();
                String reason = "confirmation deadline passed (expiresAt=" + env.getExpiresAt() + ")";
                ConfirmationResult timeout = ConfirmationResult.timeout(
                        env.getCommandId(), env.getCorrelationId(), reason);
                CommandResult result = CommandResult.builder()
                        .commandId(env.getCommandId())
                        .correlationId(env.getCorrelationId())
                        .status(CommandStatus.REJECTED)
                        .completedAt(now)
                        .errorReason("TIMEOUT: " + reason)
                        .build();
                log.warn("[{}] confirmation TIMEOUT — rejecting command", env.getCommandId());
                // Phase 8 — every rejection (including silent timeouts) must
                // produce an audit record. Coordinator handles APPROVED /
                // DENIED / non-owner; the sweeper is the only place TIMEOUT
                // is observed, so the auditor call belongs here.
                ConfirmationAuditor auditor = auditorProvider.getIfAvailable();
                if (auditor != null) {
                    try {
                        auditor.audit(env, timeout);
                    } catch (RuntimeException ex) {
                        log.warn("[{}] audit emission failed on timeout: {}",
                                env.getCommandId(), ex.getMessage());
                    }
                }
                entry.getValue().future.complete(result);
                swept++;
            }
        }
        if (swept > 0) {
            log.info("PendingConfirmationRegistry swept {} timed-out confirmation(s); {} still pending",
                    swept, pending.size());
        }
    }

    private record Pending(CommandEnvelope envelope, CompletableFuture<CommandResult> future) {}
}
