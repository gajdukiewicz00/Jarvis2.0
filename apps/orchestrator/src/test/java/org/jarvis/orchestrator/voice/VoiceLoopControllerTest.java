package org.jarvis.orchestrator.voice;

import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandSource;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.orchestrator.command.CommandPublisher;
import org.jarvis.orchestrator.command.risk.IntentRiskCatalog;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceLoopControllerTest {

    private static final String ALL_PERMS =
            "READ_FILES,WRITE_FILES,RUN_SHELL,NETWORK_ACCESS,CALENDAR_ACCESS,NOTIFICATION_ACCESS,"
            + "FINANCE_ACCESS,MEDIA_ACCESS,SMART_HOME_ACCESS,PC_CONTROL,MEMORY_ACCESS,PLANNER_ACCESS";

    private CommandPublisher publisher;
    private OrchestratorService orchestratorService;
    private SystemPanicState panicState;
    private VoiceLoopController controller;

    @BeforeEach
    void setUp() {
        publisher = mock(CommandPublisher.class);
        orchestratorService = mock(OrchestratorService.class);
        panicState = new SystemPanicState();
        controller = new VoiceLoopController(publisher, new VoiceFeedbackTemplates(),
                new VoiceIntentTranslator(), orchestratorService, new IntentRiskCatalog(),
                panicState, new ToolPermissionPolicy(ALL_PERMS));
        ReflectionTestUtils.setField(controller, "dispatchWaitSeconds", 5L);
    }

    private VoiceLoopController.VoiceLoopRequest req(String intent) {
        return new VoiceLoopController.VoiceLoopRequest(
                "vs-1", "owner", "corr-1",
                CommandSource.VOICE, intent, "включи свет", Map.of("room", "kitchen"));
    }

    @Test
    void emptyIntentReturnsUnknownIntentFeedback() {
        var req = new VoiceLoopController.VoiceLoopRequest(
                "vs-1", "owner", "corr-1", CommandSource.VOICE, " ",
                "что-то", null);
        var resp = controller.dispatch(req).getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.feedback().getCode()).isEqualTo("UNKNOWN_INTENT");
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
    }

    // --- Confirmation (MEDIUM+) intents still flow through the async queue path ---

    @Test
    void confirmationIntentSuccessReturnsCompleted() {
        // calendar.create-event = MEDIUM → requires confirmation → queue path.
        when(publisher.dispatch(eq("owner"), eq(CommandSource.VOICE), eq("calendar.create-event"),
                any(Map.class), eq("corr-1")))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandResult.success("cmd-1", "corr-1", Map.of(), 12)));
        var resp = controller.dispatch(req("calendar.create-event")).getBody();
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.COMPLETED);
        assertThat(resp.feedback().getCode()).isEqualTo("SUCCESS");
        assertThat(resp.commandId()).isEqualTo("cmd-1");
    }

    @Test
    void rejectedCommandMapsToFailedSessionAndFeedback() {
        when(publisher.dispatch(anyString(), any(), anyString(), any(Map.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        CommandResult.builder()
                                .commandId("cmd-2").correlationId("corr-1")
                                .status(CommandStatus.REJECTED)
                                .errorReason("DENIED: not now")
                                .build()));
        var resp = controller.dispatch(req("fs.delete-file")).getBody();
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
        assertThat(resp.feedback().getCode()).isEqualTo("DENIED");
    }

    @Test
    void timeoutOnFutureProducesTimeoutFeedback() {
        when(publisher.dispatch(anyString(), any(), anyString(), any(Map.class), anyString()))
                .thenReturn(new CompletableFuture<>()); // never completes
        ReflectionTestUtils.setField(controller, "dispatchWaitSeconds", 1L);
        var resp = controller.dispatch(req("home.door.unlock")).getBody();
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.EXPIRED);
        assertThat(resp.feedback().getCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void publisherRuntimeExceptionMapsToFailed() {
        // pc.app.close = MEDIUM → queue path → publisher invoked.
        when(publisher.dispatch(anyString(), any(), anyString(), any(Map.class), anyString()))
                .thenThrow(new RuntimeException("broker down"));
        var resp = controller.dispatch(req("pc.app.close")).getBody();
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
        assertThat(resp.feedback().getCode()).isEqualTo("FAILED");
        assertThat(resp.feedback().getDisplayText()).contains("broker down");
    }

    // --- SAFE/LOW intents execute synchronously via the fast-path (no queue) ---

    @Test
    void safeLowIntentExecutesSynchronouslyWithSpokenFeedback() {
        when(orchestratorService.executeIntentDetailed(
                eq("volume_down"), anyMap(), isNull(), eq("corr-1"), eq("включи свет"), eq("owner")))
                .thenReturn(new IntentExecutionResult(
                        "Уменьшаю громкость, сэр.", true, true, true, false, null));

        var resp = controller.dispatch(req("volume_down")).getBody();

        assertThat(resp).isNotNull();
        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.COMPLETED);
        assertThat(resp.feedback().getCode()).isEqualTo("SUCCESS");
        assertThat(resp.feedback().getSpokenText()).contains("громкость");
        // fast-path must NOT touch the async queue
        verify(publisher, never()).dispatch(anyString(), any(), anyString(), any(Map.class), anyString());
    }

    @Test
    void safeLowIntentExecutionFailureMapsToFailed() {
        when(orchestratorService.executeIntentDetailed(
                eq("volume_down"), anyMap(), isNull(), anyString(), anyString(), anyString()))
                .thenReturn(new IntentExecutionResult(
                        "Не удалось.", true, true, false, true, "pc-control down"));

        var resp = controller.dispatch(req("volume_down")).getBody();

        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
        assertThat(resp.feedback().getCode()).isEqualTo("FAILED");
    }

    @Test
    void panicEngagedRefusesVoiceDispatchAndExecutesNothing() {
        panicState.engage("test", "drill", 1L);

        var resp = controller.dispatch(req("volume_down")).getBody();

        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
        assertThat(resp.feedback().getCode()).isEqualTo("DEGRADED");
        verify(orchestratorService, never()).executeIntentDetailed(any(), any(), any(), any(), any(), any());
        verify(publisher, never()).dispatch(anyString(), any(), anyString(), any(Map.class), anyString());
    }

    @Test
    void deniedPermissionRefusesVoiceDispatchRegardlessOfEntryPoint() {
        // PC_CONTROL not granted -> volume_down (PC_CONTROL) must be blocked
        VoiceLoopController restricted = new VoiceLoopController(publisher, new VoiceFeedbackTemplates(),
                new VoiceIntentTranslator(), orchestratorService, new IntentRiskCatalog(),
                new SystemPanicState(), new ToolPermissionPolicy("PLANNER_ACCESS"));
        ReflectionTestUtils.setField(restricted, "dispatchWaitSeconds", 5L);

        var resp = restricted.dispatch(req("volume_down")).getBody();

        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.FAILED);
        assertThat(resp.feedback().getCode()).isEqualTo("DENIED");
        verify(orchestratorService, never()).executeIntentDetailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelIntentMarksCancelledAndDispatchesNothing() {
        var resp = controller.dispatch(req("cancel")).getBody();

        assertThat(resp.sessionStatus()).isEqualTo(VoiceSessionStatus.CANCELLED);
        assertThat(resp.feedback().getCode()).isEqualTo("CANCELLED");
        verify(orchestratorService, never()).executeIntentDetailed(any(), any(), any(), any(), any(), any());
        verify(publisher, never()).dispatch(anyString(), any(), anyString(), any(Map.class), anyString());
    }
}
