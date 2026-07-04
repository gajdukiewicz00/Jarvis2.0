package org.jarvis.orchestrator.voice;

import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.voice.VoiceFeedback;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceFeedbackTemplatesTest {

    private final VoiceFeedbackTemplates t = new VoiceFeedbackTemplates();

    private CommandResult resultWith(CommandStatus status, String reason) {
        return CommandResult.builder()
                .commandId("cmd-1")
                .correlationId("corr-1")
                .status(status)
                .errorReason(reason)
                .durationMillis(7)
                .output(Map.of())
                .build();
    }

    @Test
    void successMapsToInfoCode() {
        VoiceFeedback fb = t.fromCommandResult(resultWith(CommandStatus.SUCCESS, null));
        assertThat(fb.getCode()).isEqualTo("SUCCESS");
        assertThat(fb.getLevel()).isEqualTo(VoiceFeedback.Level.INFO);
        assertThat(fb.getSpokenText()).contains("Готово");
    }

    @Test
    void rejectedDemoModeRecognised() {
        VoiceFeedback fb = t.fromCommandResult(
                resultWith(CommandStatus.REJECTED, "BLOCKED_DEMO_MODE: demo mode active"));
        assertThat(fb.getCode()).isEqualTo("BLOCKED_DEMO_MODE");
        assertThat(fb.getSpokenText()).containsIgnoringCase("демо");
    }

    @Test
    void rejectedNonOwnerRecognised() {
        VoiceFeedback fb = t.fromCommandResult(
                resultWith(CommandStatus.REJECTED, "BLOCKED_NON_OWNER: speaker 'guest'"));
        assertThat(fb.getCode()).isEqualTo("BLOCKED_NON_OWNER");
        assertThat(fb.getLevel()).isEqualTo(VoiceFeedback.Level.ERROR);
    }

    @Test
    void rejectedTimeoutMapsToTimeout() {
        VoiceFeedback fb = t.fromCommandResult(
                resultWith(CommandStatus.REJECTED, "TIMEOUT: owner away"));
        assertThat(fb.getCode()).isEqualTo("TIMEOUT");
        assertThat(fb.getLevel()).isEqualTo(VoiceFeedback.Level.WARN);
    }

    @Test
    void rejectedDeniedDefault() {
        VoiceFeedback fb = t.fromCommandResult(
                resultWith(CommandStatus.REJECTED, "DENIED: not now"));
        assertThat(fb.getCode()).isEqualTo("DENIED");
    }

    @Test
    void expiredMapsCleanly() {
        VoiceFeedback fb = t.fromCommandResult(resultWith(CommandStatus.EXPIRED, "ttl"));
        assertThat(fb.getCode()).isEqualTo("EXPIRED");
        assertThat(fb.getSpokenText()).contains("устарела");
    }

    @Test
    void failedHasErrorLevel() {
        VoiceFeedback fb = t.fromCommandResult(resultWith(CommandStatus.FAILED, "boom"));
        assertThat(fb.getCode()).isEqualTo("FAILED");
        assertThat(fb.getLevel()).isEqualTo(VoiceFeedback.Level.ERROR);
        assertThat(fb.getDisplayText()).contains("boom");
    }

    @Test
    void awaitingConfirmationHasWarnLevel() {
        VoiceFeedback fb = t.fromCommandResult(resultWith(CommandStatus.AWAITING_CONFIRMATION, null));
        assertThat(fb.getCode()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(fb.getLevel()).isEqualTo(VoiceFeedback.Level.WARN);
    }

    @Test
    void inFlightStatusReturnsProcessing() {
        VoiceFeedback fb = t.fromCommandResult(resultWith(CommandStatus.QUEUED, null));
        assertThat(fb.getCode()).isEqualTo("PROCESSING");
        assertThat(fb.getSpokenText()).contains("Минуту");
    }

    @Test
    void unknownIntentTemplate() {
        VoiceFeedback fb = t.unknownIntent("включи свет на крыше дома");
        assertThat(fb.getCode()).isEqualTo("UNKNOWN_INTENT");
        assertThat(fb.getDisplayText()).contains("включи свет");
    }

    @Test
    void clarifyTemplate() {
        VoiceFeedback fb = t.clarify("ambiguous slot");
        assertThat(fb.getCode()).isEqualTo("CLARIFY");
        assertThat(fb.getSpokenText()).contains("Уточните");
    }

    @Test
    void nullResultProducesFailed() {
        assertThat(t.fromCommandResult(null).getCode()).isEqualTo("FAILED");
    }
}
