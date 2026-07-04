package org.jarvis.orchestrator.command.confirmation;

import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.ConfirmationDecision;
import org.jarvis.commands.ConfirmationResult;
import org.jarvis.commands.RiskLevel;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.orchestrator.command.CommandPublisher;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationCoordinatorTest {

    private RabbitTemplate rabbitTemplate;
    private PendingConfirmationRegistry pendingConfirmations;
    private DemoModeProperties demoMode;
    private IntentRiskCatalog catalog;
    private CommandPublisher publisher;
    private ConfirmationAuditor auditor;
    private ConfirmationCoordinator coordinator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        ObjectProvider<AuditPublisher> noopAuditProvider = mock(ObjectProvider.class);
        when(noopAuditProvider.getIfAvailable()).thenReturn(null);
        auditor = new ConfirmationAuditor(noopAuditProvider);
        ObjectProvider<ConfirmationAuditor> auditorProvider = mock(ObjectProvider.class);
        when(auditorProvider.getIfAvailable()).thenReturn(auditor);
        pendingConfirmations = new PendingConfirmationRegistry(auditorProvider);
        ReflectionTestUtils.setField(pendingConfirmations, "sweepIntervalMs", 1_000L);
        demoMode = new DemoModeProperties();
        catalog = new IntentRiskCatalog();
        publisher = mock(CommandPublisher.class);
        coordinator = new ConfirmationCoordinator(
                rabbitTemplate, pendingConfirmations, demoMode, catalog, publisher, auditor);
    }

    private CommandEnvelope dangerousEnvelope(String userId, String intent) {
        return CommandEnvelope.builder()
                .commandId("cmd-1")
                .correlationId("trace-1")
                .userId(userId)
                .source(CommandSource.VOICE)
                .intent(intent)
                .riskLevel(RiskLevel.HIGH)
                .requiresConfirmation(true)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(30))
                .status(CommandStatus.CREATED)
                .payload(new HashMap<>())
                .build();
    }

    @Test
    void demoModeShortCircuitsBeforePublishingConfirmationRequest() throws Exception {
        demoMode.setEnabled(true);
        CommandEnvelope env = dangerousEnvelope("owner", "fs.delete-file");

        CompletableFuture<CommandResult> future = coordinator.stageConfirmation(env);

        assertThat(future).isDone();
        CommandResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.getErrorReason()).contains("BLOCKED_DEMO_MODE");
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void approvedByOwnerForwardsToExecuteQueue() throws Exception {
        CommandEnvelope env = dangerousEnvelope("owner", "fs.delete-file");
        // mock publisher.publish to return a completed SUCCESS future
        CompletableFuture<CommandResult> execFuture = CompletableFuture.completedFuture(
                CommandResult.success(env.getCommandId(), env.getCorrelationId(), Map.of(), 5));
        when(publisher.publish(any(CommandEnvelope.class))).thenReturn(execFuture);

        CompletableFuture<CommandResult> caller = coordinator.stageConfirmation(env);
        // pending until decision arrives
        assertThat(caller).isNotDone();

        ConfirmationResult decision = ConfirmationResult.builder()
                .commandId(env.getCommandId())
                .correlationId(env.getCorrelationId())
                .decision(ConfirmationDecision.APPROVED)
                .decidedBy("owner")
                .decidedAt(Instant.now())
                .channel("desktop")
                .build();
        coordinator.handleDecision(decision);

        verify(publisher, times(1)).publish(env);
        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.SUCCESS);
    }

    @Test
    void approvedByNonOwnerIsBlocked() throws Exception {
        CommandEnvelope env = dangerousEnvelope("owner", "fs.delete-file");
        CompletableFuture<CommandResult> caller = coordinator.stageConfirmation(env);

        ConfirmationResult decision = ConfirmationResult.builder()
                .commandId(env.getCommandId())
                .correlationId(env.getCorrelationId())
                .decision(ConfirmationDecision.APPROVED)
                .decidedBy("guest")          // ← non-owner
                .decidedAt(Instant.now())
                .channel("voice")
                .build();
        coordinator.handleDecision(decision);

        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.getErrorReason()).contains("BLOCKED_NON_OWNER");
        verify(publisher, never()).publish(any(CommandEnvelope.class));
    }

    @Test
    void deniedDecisionRejectsCommand() throws Exception {
        CommandEnvelope env = dangerousEnvelope("owner", "fs.delete-file");
        CompletableFuture<CommandResult> caller = coordinator.stageConfirmation(env);

        ConfirmationResult decision = ConfirmationResult.builder()
                .commandId(env.getCommandId())
                .correlationId(env.getCorrelationId())
                .decision(ConfirmationDecision.DENIED)
                .decidedBy("owner")
                .decidedAt(Instant.now())
                .channel("desktop")
                .reason("not now")
                .build();
        coordinator.handleDecision(decision);

        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.getErrorReason()).contains("DENIED");
        verify(publisher, never()).publish(any(CommandEnvelope.class));
    }

    @Test
    void timeoutDecisionRejectsCommand() throws Exception {
        CommandEnvelope env = dangerousEnvelope("owner", "fs.delete-file");
        CompletableFuture<CommandResult> caller = coordinator.stageConfirmation(env);

        coordinator.handleDecision(ConfirmationResult.timeout(
                env.getCommandId(), env.getCorrelationId(), "owner away"));

        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.getErrorReason()).contains("TIMEOUT");
        verify(publisher, never()).publish(any(CommandEnvelope.class));
    }

    @Test
    void registryTimeoutSweepsExpiredConfirmation() throws Exception {
        // Build an envelope whose deadline is already in the past so the sweeper picks it up.
        CommandEnvelope env = CommandEnvelope.builder()
                .commandId("cmd-old")
                .correlationId("trace-1")
                .userId("owner")
                .source(CommandSource.VOICE)
                .intent("fs.delete-file")
                .riskLevel(RiskLevel.HIGH)
                .requiresConfirmation(true)
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().minusSeconds(1))
                .status(CommandStatus.AWAITING_CONFIRMATION)
                .build();

        CompletableFuture<CommandResult> caller = pendingConfirmations.register(env);
        pendingConfirmations.sweepExpired();

        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(result.getErrorReason()).contains("TIMEOUT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registryTimeoutEmitsAuditEvent() throws Exception {
        // Real publisher mock so we can verify .audit(...) is called on TIMEOUT.
        AuditPublisher realPublisher = mock(AuditPublisher.class);
        ObjectProvider<AuditPublisher> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(realPublisher);
        ConfirmationAuditor liveAuditor = new ConfirmationAuditor(auditProvider);
        ObjectProvider<ConfirmationAuditor> liveAuditorProvider = mock(ObjectProvider.class);
        when(liveAuditorProvider.getIfAvailable()).thenReturn(liveAuditor);
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(liveAuditorProvider);
        ReflectionTestUtils.setField(registry, "sweepIntervalMs", 1_000L);

        CommandEnvelope env = CommandEnvelope.builder()
                .commandId("cmd-timeout-audit")
                .correlationId("trace-9")
                .userId("owner")
                .source(CommandSource.VOICE)
                .intent("fs.delete-file")
                .riskLevel(RiskLevel.HIGH)
                .requiresConfirmation(true)
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().minusSeconds(1))
                .status(CommandStatus.AWAITING_CONFIRMATION)
                .build();

        CompletableFuture<CommandResult> caller = registry.register(env);
        registry.sweepExpired();

        CommandResult result = caller.get(1, TimeUnit.SECONDS);
        assertThat(result.getStatus()).isEqualTo(CommandStatus.REJECTED);
        verify(realPublisher, times(1)).audit(
                eq(AuditEventType.CONFIRMATION_TIMEOUT),
                eq("trace-9"),
                eq(null),
                eq("owner"),
                eq("cmd-timeout-audit"),
                any());
    }
}
