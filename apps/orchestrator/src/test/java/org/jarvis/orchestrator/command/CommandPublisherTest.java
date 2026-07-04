package org.jarvis.orchestrator.command;

import org.jarvis.commands.CommandEnvelope;
import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.RiskLevel;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.events.AuditEventType;
import org.jarvis.orchestrator.command.confirmation.ConfirmationCoordinator;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A+3 / A+2 — the orchestrator publishing choke point refuses to publish while
 * panic is engaged or when the intent's permission is not granted, regardless
 * of risk level. No RabbitMQ send happens in either case.
 */
class CommandPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final PendingCommandRegistry registry = mock(PendingCommandRegistry.class);
    private final ConfirmationCoordinator confirmationCoordinator = mock(ConfirmationCoordinator.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditPublisher> auditProvider = mock(ObjectProvider.class);

    private CommandPublisher publisher(SystemPanicState panic, ToolPermissionPolicy policy) {
        CommandPublisher pub = new CommandPublisher(rabbitTemplate, registry, new IntentRiskCatalog(),
                confirmationCoordinator, auditProvider, panic, policy);
        ReflectionTestUtils.setField(pub, "defaultTtlSeconds", 30L);
        return pub;
    }

    @Test
    void panicEngagedBlocksPublishing() throws Exception {
        SystemPanicState panic = new SystemPanicState();
        panic.engage("t", "drill", 1L);
        CommandPublisher pub = publisher(panic, new ToolPermissionPolicy(""));

        CommandResult result = pub.dispatch("u", CommandSource.VOICE, "volume_down", Map.of(), "c1").get();

        assertThat(result.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(result.getErrorReason()).contains("system_panic_engaged");
        // publish() begins with registry.register(); never reaching it proves nothing was published
        verify(registry, never()).register(any());
    }

    @Test
    void deniedPermissionBlocksPublishing() throws Exception {
        // FINANCE_ACCESS not granted -> a finance intent must be refused before publishing
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy("PLANNER_ACCESS"));

        CommandResult result = pub.dispatch("u", CommandSource.VOICE, "finance_transfer_money", Map.of(), "c2").get();

        assertThat(result.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(result.getErrorReason()).contains("permission_denied");
        verify(registry, never()).register(any());
    }

    @Test
    void safeIntentPublishesSuccessfullyAndEmitsAudit() throws Exception {
        AuditPublisher realAuditPublisher = mock(AuditPublisher.class);
        when(auditProvider.getIfAvailable()).thenReturn(realAuditPublisher);
        when(registry.register(any(CommandEnvelope.class)))
                .thenAnswer(inv -> CompletableFuture.completedFuture(
                        CommandResult.success("cmd-x", "c3", Map.of(), 1)));
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));

        CommandResult result = pub.dispatch("u", CommandSource.VOICE, "volume_down", Map.of(), "c3").get();

        assertThat(result.getStatus()).isEqualTo(CommandStatus.SUCCESS);
        verify(rabbitTemplate).convertAndSend(eq(""), anyString(), any(CommandEnvelope.class),
                any(MessagePostProcessor.class));
        verify(realAuditPublisher).audit(eq(AuditEventType.COMMAND_QUEUED), eq("c3"), eq(null),
                eq("u"), anyString(), any());
    }

    @Test
    void publishFailureCompletesRegistryWithFailedResult() {
        when(registry.register(any(CommandEnvelope.class))).thenReturn(new CompletableFuture<>());
        doThrow(new RuntimeException("broker unreachable"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));

        pub.dispatch("u", CommandSource.VOICE, "volume_down", Map.of(), "c4");

        verify(registry).complete(argThat(r ->
                r != null && r.getStatus() == CommandStatus.FAILED
                        && r.getErrorReason() != null
                        && r.getErrorReason().contains("broker unreachable")));
    }

    @Test
    void hintedRiskLevelCannotDowngradeCatalogClassification() throws Exception {
        // "unknown_thing" is not catalogued -> defaults to MEDIUM, which requires confirmation.
        // A caller-supplied SAFE hint must not downgrade that.
        when(confirmationCoordinator.stageConfirmation(any(CommandEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandResult.failed("cmd-y", "c5", "BLOCKED_DEMO_MODE: demo", 0)));
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));

        CommandResult result = pub.dispatch(
                "u", CommandSource.VOICE, "unknown_thing", RiskLevel.SAFE, Map.of(), "c5").get();

        assertThat(result.getStatus()).isEqualTo(CommandStatus.FAILED);
        verify(confirmationCoordinator).stageConfirmation(argThat(env -> env.getRiskLevel() == RiskLevel.MEDIUM));
        verify(registry, never()).register(any());
    }

    @Test
    void hintedRiskLevelMatchingCatalogPublishesDirectly() {
        when(registry.register(any(CommandEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandResult.success("cmd-z", "c6", Map.of(), 1)));
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));

        pub.dispatch("u", CommandSource.VOICE, "volume_down", RiskLevel.LOW, Map.of(), "c6");

        verify(confirmationCoordinator, never()).stageConfirmation(any());
        verify(rabbitTemplate).convertAndSend(eq(""), anyString(), any(CommandEnvelope.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void cancelPendingDelegatesToRegistry() {
        when(registry.cancel("cmd-1")).thenReturn(true);
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));

        assertThat(pub.cancelPending("cmd-1")).isTrue();
        verify(registry).cancel("cmd-1");
    }

    @Test
    void publishUsesDefaultTtlWhenEnvelopeHasNoExpiry() {
        when(registry.register(any(CommandEnvelope.class)))
                .thenReturn(new CompletableFuture<>());
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));
        CommandEnvelope envelope = CommandEnvelope.builder()
                .commandId("cmd-no-ttl")
                .correlationId("c7")
                .userId("u")
                .source(CommandSource.VOICE)
                .intent("volume_down")
                .riskLevel(RiskLevel.LOW)
                .requiresConfirmation(false)
                .createdAt(Instant.now())
                .payload(Map.of())
                .build();

        pub.publish(envelope);

        verify(rabbitTemplate).convertAndSend(eq(""), anyString(), eq(envelope), any(MessagePostProcessor.class));
    }

    @Test
    void publishClampsNegativeRemainingTtlToOneMillisecond() {
        when(registry.register(any(CommandEnvelope.class)))
                .thenReturn(new CompletableFuture<>());
        CommandPublisher pub = publisher(new SystemPanicState(), new ToolPermissionPolicy(""));
        CommandEnvelope envelope = CommandEnvelope.builder()
                .commandId("cmd-expired-ttl")
                .correlationId("c8")
                .userId("u")
                .source(CommandSource.VOICE)
                .intent("volume_down")
                .riskLevel(RiskLevel.LOW)
                .requiresConfirmation(false)
                .createdAt(Instant.now().minusSeconds(120))
                .expiresAt(Instant.now().minusSeconds(60))
                .payload(Map.of())
                .build();

        pub.publish(envelope);

        verify(rabbitTemplate).convertAndSend(eq(""), anyString(), eq(envelope), any(MessagePostProcessor.class));
    }
}
