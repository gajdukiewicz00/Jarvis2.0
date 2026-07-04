package org.jarvis.orchestrator.command;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4 — in-memory registry of commands the orchestrator has published
 * and is awaiting a result for.
 *
 * <p>Backed by a ConcurrentHashMap keyed by {@code commandId}. A scheduled
 * sweeper completes any command whose deadline has passed with an
 * {@code EXPIRED} result, so callers don't block forever if the broker or
 * agent loses the result message.</p>
 *
 * <p>Idempotency is enforced on the consumer side (the desktop-agent's
 * {@code CommandIdempotencyStore}). The publisher side just makes sure not
 * to register the same {@code commandId} twice.</p>
 */
@Slf4j
@Component
public class PendingCommandRegistry {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Value("${jarvis.command.pending.sweep-interval-ms:1000}")
    private long sweepIntervalMs;

    public CompletableFuture<CommandResult> register(CommandEnvelope envelope) {
        Pending existing = pending.get(envelope.getCommandId());
        if (existing != null) {
            log.warn("Duplicate command_id registered: {} (intent={}, ignoring re-register)",
                    envelope.getCommandId(), envelope.getIntent());
            return existing.future;
        }
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        pending.put(envelope.getCommandId(), new Pending(envelope, future));
        log.info("[{}] command queued: intent={} risk={} expiresAt={}",
                envelope.getCommandId(), envelope.getIntent(),
                envelope.getRiskLevel(), envelope.getExpiresAt());
        return future;
    }

    public boolean complete(CommandResult result) {
        Pending removed = pending.remove(result.getCommandId());
        if (removed == null) {
            log.debug("[{}] result for unknown/late command (status={}) — ignoring",
                    result.getCommandId(), result.getStatus());
            return false;
        }
        log.info("[{}] command completed: status={} duration={}ms",
                result.getCommandId(), result.getStatus(), result.getDurationMillis());
        removed.future.complete(result);
        return true;
    }

    /**
     * B1 — cancel a pending command before its result arrives. Completes the
     * waiting future with a FAILED("cancelled_by_user") result and removes it,
     * so the voice dispatch unblocks immediately and the tool is not awaited.
     * Returns false if the command is unknown / already completed.
     */
    public boolean cancel(String commandId) {
        Pending removed = pending.remove(commandId);
        if (removed == null) {
            return false;
        }
        CommandResult cancelled = CommandResult.failed(
                commandId, removed.envelope.getCorrelationId(), "cancelled_by_user", 0);
        removed.future.complete(cancelled);
        log.info("[{}] command CANCELLED by user before result arrived", commandId);
        return true;
    }

    public int size() {
        return pending.size();
    }

    public boolean isPending(String commandId) {
        return pending.containsKey(commandId);
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
                String reason = "expired locally before result arrived (deadline="
                        + env.getExpiresAt() + ")";
                log.warn("[{}] {} — completing with EXPIRED", env.getCommandId(), reason);
                CommandResult expired = CommandResult.builder()
                        .commandId(env.getCommandId())
                        .correlationId(env.getCorrelationId())
                        .status(CommandStatus.EXPIRED)
                        .completedAt(now)
                        .errorReason(reason)
                        .build();
                entry.getValue().future.complete(expired);
                swept++;
            }
        }
        if (swept > 0) {
            log.info("PendingCommandRegistry swept {} expired command(s); {} still pending",
                    swept, pending.size());
        }
    }

    private record Pending(CommandEnvelope envelope, CompletableFuture<CommandResult> future) {}

    /** Test-only convenience: synchronously wait with a hard cap. */
    public CommandResult awaitResult(String commandId, Duration timeout) {
        Pending p = pending.get(commandId);
        if (p == null) {
            return null;
        }
        try {
            return p.future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            return CommandResult.failed(commandId, p.envelope.getCorrelationId(),
                    "wait interrupted: " + ex.getMessage(), 0);
        }
    }
}
