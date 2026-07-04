package org.jarvis.orchestrator.command;

import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.orchestrator.command.confirmation.ConfirmationCoordinator;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
}
