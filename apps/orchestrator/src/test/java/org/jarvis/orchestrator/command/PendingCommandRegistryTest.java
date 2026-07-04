package org.jarvis.orchestrator.command;

import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandFactory;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PendingCommandRegistryTest {

    private PendingCommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PendingCommandRegistry();
    }

    @Test
    void completesPendingFutureWhenResultArrives() throws Exception {
        CommandEnvelope env = CommandFactory.create(
                "user-1", CommandSource.VOICE, "pc.window.focus",
                RiskLevel.LOW, Map.of("window", "Firefox"),
                Duration.ofSeconds(30), null);

        CompletableFuture<CommandResult> future = registry.register(env);
        assertThat(future).isNotDone();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.isPending(env.getCommandId())).isTrue();

        CommandResult result = CommandResult.success(
                env.getCommandId(), env.getCorrelationId(),
                Map.of("ok", true), 12);
        boolean matched = registry.complete(result);

        assertThat(matched).isTrue();
        assertThat(future.get(1, TimeUnit.SECONDS).getStatus())
                .isEqualTo(CommandStatus.SUCCESS);
        assertThat(registry.size()).isZero();
    }

    @Test
    void duplicateRegistrationReturnsSameFuture() {
        CommandEnvelope env = CommandFactory.create(
                "user-1", CommandSource.VOICE, "intent",
                RiskLevel.SAFE, Map.of(), Duration.ofSeconds(30), null);

        CompletableFuture<CommandResult> first = registry.register(env);
        CompletableFuture<CommandResult> second = registry.register(env);

        assertThat(second).isSameAs(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void completeReturnsFalseForUnknownCommand() {
        CommandResult orphan = CommandResult.success(
                "cmd-unknown", "trace-1", Map.of(), 0);
        assertThat(registry.complete(orphan)).isFalse();
    }

    @Test
    void sweepCompletesExpiredAsExpired() throws Exception {
        Instant past = Instant.now().minusSeconds(5);
        CommandEnvelope env = CommandEnvelope.builder()
                .commandId("cmd-old")
                .correlationId("trace-1")
                .userId("user-1")
                .source(CommandSource.VOICE)
                .intent("intent")
                .riskLevel(RiskLevel.SAFE)
                .createdAt(past.minusSeconds(10))
                .expiresAt(past)
                .status(CommandStatus.QUEUED)
                .build();

        CompletableFuture<CommandResult> future = registry.register(env);
        registry.sweepExpired();

        assertThat(future).isDone();
        CommandResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.EXPIRED);
        assertThat(result.getErrorReason()).contains("expired locally");
        assertThat(registry.size()).isZero();
    }

    @Test
    void cancelCompletesFutureWithCancelledReasonAndRemoves() throws Exception {
        CommandEnvelope env = CommandFactory.create(
                "user-1", CommandSource.VOICE, "volume_down",
                RiskLevel.LOW, Map.of(), Duration.ofSeconds(30), "corr-1");

        CompletableFuture<CommandResult> future = registry.register(env);
        boolean cancelled = registry.cancel(env.getCommandId());

        assertThat(cancelled).isTrue();
        assertThat(registry.isPending(env.getCommandId())).isFalse();
        CommandResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(result.getErrorReason()).contains("cancelled_by_user");
    }

    @Test
    void cancelUnknownCommandReturnsFalse() {
        assertThat(registry.cancel("does-not-exist")).isFalse();
    }

    @Test
    void sweepLeavesNonExpiredAlone() {
        CommandEnvelope env = CommandFactory.create(
                "user-1", CommandSource.VOICE, "intent",
                RiskLevel.SAFE, Map.of(), Duration.ofSeconds(60), null);

        registry.register(env);
        registry.sweepExpired();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.isPending(env.getCommandId())).isTrue();
    }
}
