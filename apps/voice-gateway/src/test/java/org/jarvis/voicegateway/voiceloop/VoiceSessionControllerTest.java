package org.jarvis.voicegateway.voiceloop;

import org.jarvis.commands.voice.VoiceFeedback;
import org.jarvis.commands.voice.VoiceSession;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceSessionControllerTest {

    @Mock
    private VoiceSessionRegistry registry;
    @Mock
    private IntentResolver intents;
    @Mock
    private OrchestratorVoiceClient orchestratorClient;

    private VoiceSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new VoiceSessionController(registry, intents, orchestratorClient);
    }

    @Test
    void startCreatesSessionWithDefaultsWhenFieldsAreNull() {
        VoiceSession session = VoiceSession.builder().sessionId("vs-1").build();
        when(registry.start("unknown-agent", "anonymous")).thenReturn(session);

        ResponseEntity<VoiceSession> response = controller.start(new VoiceSessionController.StartRequest(null, null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("vs-1", response.getBody().getSessionId());
    }

    @Test
    void startCreatesSessionWithProvidedAgentAndUser() {
        VoiceSession session = VoiceSession.builder().sessionId("vs-2").build();
        when(registry.start("agent-9", "user-9")).thenReturn(session);

        ResponseEntity<VoiceSession> response = controller.start(new VoiceSessionController.StartRequest("agent-9", "user-9"));

        assertEquals("vs-2", response.getBody().getSessionId());
    }

    @Test
    void utteranceReturns404WhenSessionMissing() {
        when(registry.get("missing")).thenReturn(Optional.empty());

        ResponseEntity<VoiceSessionController.UtteranceResponse> response =
                controller.utterance("missing", new VoiceSessionController.UtteranceRequest("hi", "ru"));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void utteranceReturns400WhenTranscriptBlank() {
        when(registry.get("s1")).thenReturn(Optional.of(VoiceSession.builder().sessionId("s1").build()));

        ResponseEntity<VoiceSessionController.UtteranceResponse> response =
                controller.utterance("s1", new VoiceSessionController.UtteranceRequest("  ", "ru"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void utteranceReturns400WhenTranscriptNull() {
        when(registry.get("s1")).thenReturn(Optional.of(VoiceSession.builder().sessionId("s1").build()));

        ResponseEntity<VoiceSessionController.UtteranceResponse> response =
                controller.utterance("s1", new VoiceSessionController.UtteranceRequest(null, "ru"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void utteranceReturnsUnknownIntentFeedbackWhenNotResolved() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").userId("user-1").build();
        when(registry.get("s1")).thenReturn(Optional.of(session));
        when(intents.resolve("непонятно", "ru")).thenReturn(IntentResolver.Resolution.empty());

        ResponseEntity<VoiceSessionController.UtteranceResponse> response =
                controller.utterance("s1", new VoiceSessionController.UtteranceRequest("непонятно", "ru"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(VoiceSessionStatus.FAILED, response.getBody().sessionStatus());
        assertEquals("UNKNOWN_INTENT", response.getBody().feedback().getCode());
        // Once for the TRANSCRIBED transition, once more for the FAILED transition.
        verify(registry, times(2)).update(eq("s1"), any());
    }

    @Test
    void utteranceDispatchesToOrchestratorWhenIntentResolved() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").userId("user-1").build();
        when(registry.get("s1")).thenReturn(Optional.of(session));
        when(intents.resolve("громче", "ru")).thenReturn(new IntentResolver.Resolution("VOLUME_UP", "regex", 0.8));

        OrchestratorVoiceClient.VoiceLoopReply reply = new OrchestratorVoiceClient.VoiceLoopReply(
                "cmd-1", "corr-x", VoiceSessionStatus.COMPLETED,
                VoiceFeedback.builder().code("SUCCESS").level(VoiceFeedback.Level.INFO).spokenText("Готово").build());
        when(orchestratorClient.dispatch(eq("s1"), eq("user-1"), anyString(), eq("VOLUME_UP"), eq("громче")))
                .thenReturn(reply);

        ResponseEntity<VoiceSessionController.UtteranceResponse> response =
                controller.utterance("s1", new VoiceSessionController.UtteranceRequest("громче", "ru"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("cmd-1", response.getBody().commandId());
        assertEquals(VoiceSessionStatus.COMPLETED, response.getBody().sessionStatus());
        // TRANSCRIBED, then CLASSIFIED, then the final reply-status transition.
        verify(registry, times(3)).update(eq("s1"), any());
    }

    @Test
    void endDelegatesToRegistryAndReturnsAccepted() {
        ResponseEntity<Void> response = controller.end("s1");

        assertEquals(202, response.getStatusCode().value());
        verify(registry).end("s1");
    }

    @Test
    void cancelReturnsOkWhenSessionExists() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").status(VoiceSessionStatus.CANCELLED).build();
        when(registry.cancel("s1", "user_cancel")).thenReturn(Optional.of(session));

        ResponseEntity<VoiceSession> response = controller.cancel("s1", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(VoiceSessionStatus.CANCELLED, response.getBody().getStatus());
    }

    @Test
    void cancelUsesProvidedReason() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").build();
        when(registry.cancel("s1", "barge-in")).thenReturn(Optional.of(session));

        controller.cancel("s1", new VoiceSessionController.CancelRequest("barge-in"));

        verify(registry).cancel("s1", "barge-in");
    }

    @Test
    void cancelReturns404WhenSessionMissing() {
        when(registry.cancel("missing", "user_cancel")).thenReturn(Optional.empty());

        ResponseEntity<VoiceSession> response = controller.cancel("missing", null);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getReturnsSessionWhenPresent() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").build();
        when(registry.get("s1")).thenReturn(Optional.of(session));

        ResponseEntity<VoiceSession> response = controller.get("s1");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getReturns404WhenMissing() {
        when(registry.get("missing")).thenReturn(Optional.empty());

        ResponseEntity<VoiceSession> response = controller.get("missing");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void listDelegatesToRegistry() {
        VoiceSession session = VoiceSession.builder().sessionId("s1").build();
        when(registry.list()).thenReturn(List.of(session));

        assertEquals(List.of(session), controller.list());
    }
}
