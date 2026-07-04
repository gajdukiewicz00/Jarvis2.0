package org.jarvis.orchestrator.command;

import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandResultListenerTest {

    private PendingCommandRegistry registry;
    private AuditPublisher audit;
    private CommandResultListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(PendingCommandRegistry.class);
        when(registry.complete(any())).thenReturn(true);
        audit = mock(AuditPublisher.class);
        ObjectProvider<AuditPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        when(provider.stream()).thenReturn(Stream.of(audit));
        listener = new CommandResultListener(registry, provider);
    }

    @Test
    void successResultEmitsCommandExecuted() {
        listener.onResult(CommandResult.success("cmd-1", "corr-1", Map.of("exit", 0), 42L));
        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(audit).audit(eq(AuditEventType.COMMAND_EXECUTED), eq("corr-1"), eq(null), eq(null),
                eq("cmd-1"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("status", "SUCCESS")
                .containsEntry("matchedPending", true)
                .containsEntry("durationMillis", 42L)
                .containsKey("output");
    }

    @Test
    void failedResultEmitsCommandFailed() {
        listener.onResult(CommandResult.failed("cmd-2", "corr-2", "exit code 7", 100L));
        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(audit).audit(eq(AuditEventType.COMMAND_FAILED), eq("corr-2"), eq(null), eq(null),
                eq("cmd-2"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("status", "FAILED")
                .containsEntry("errorReason", "exit code 7");
    }

    @Test
    void rejectedResultEmitsCommandFailed() {
        CommandResult rejected = CommandResult.builder()
                .commandId("cmd-3").correlationId("corr-3")
                .status(CommandStatus.REJECTED).errorReason("confirmation_denied")
                .build();
        listener.onResult(rejected);
        verify(audit).audit(eq(AuditEventType.COMMAND_FAILED), eq("corr-3"), eq(null), eq(null),
                eq("cmd-3"), any());
    }

    @Test
    void expiredResultEmitsCommandExpired() {
        listener.onResult(CommandResult.expired("cmd-4", "corr-4", "agent_offline"));
        verify(audit).audit(eq(AuditEventType.COMMAND_EXPIRED), eq("corr-4"), eq(null), eq(null),
                eq("cmd-4"), any());
    }

    @Test
    void nonTerminalStatusDoesNotEmit() {
        CommandResult odd = CommandResult.builder()
                .commandId("cmd-5").correlationId("corr-5")
                .status(CommandStatus.EXECUTING)
                .build();
        listener.onResult(odd);
        verify(audit, never()).audit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nullCommandIdIsIgnoredAndNotAudited() {
        listener.onResult(CommandResult.builder().status(CommandStatus.SUCCESS).build());
        verify(audit, never()).audit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nullResultIsIgnoredWithoutCrash() {
        listener.onResult(null);
        verify(audit, never()).audit(any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }
}
