package org.jarvis.voicegateway.voiceloop;

import org.jarvis.commands.voice.VoiceFeedback;
import org.jarvis.commands.voice.VoiceSession;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VoiceSessionControllerTest mocks VoiceSessionRegistry, so the mutator lambdas
 * passed to registry.update(sessionId, session -> {...}) are captured but never
 * actually invoked by the mock. This test uses the real registry so those
 * lambda bodies run for real and their effect on the stored session is
 * observable end-to-end.
 */
class VoiceSessionControllerRealRegistryTest {

    private VoiceSessionRegistry registry;
    private IntentResolver intents;
    private OrchestratorVoiceClient orchestratorClient;
    private VoiceSessionController controller;

    @BeforeEach
    void setUp() {
        registry = new VoiceSessionRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 120L);
        intents = mock(IntentResolver.class);
        orchestratorClient = mock(OrchestratorVoiceClient.class);
        controller = new VoiceSessionController(registry, intents, orchestratorClient);
    }

    @Test
    void utteranceUpdatesRealSessionThroughTranscribedClassifiedAndFinalStatus() {
        VoiceSession session = registry.start("agent-1", "user-1");
        when(intents.resolve("громче", "ru")).thenReturn(new IntentResolver.Resolution("VOLUME_UP", "regex", 0.9));
        OrchestratorVoiceClient.VoiceLoopReply reply = new OrchestratorVoiceClient.VoiceLoopReply(
                "cmd-1", "corr-x", VoiceSessionStatus.COMPLETED,
                VoiceFeedback.builder().code("SUCCESS").level(VoiceFeedback.Level.INFO).spokenText("Готово").build());
        when(orchestratorClient.dispatch(eq(session.getSessionId()), eq("user-1"), anyString(), eq("VOLUME_UP"), eq("громче")))
                .thenReturn(reply);

        ResponseEntity<VoiceSessionController.UtteranceResponse> response = controller.utterance(
                session.getSessionId(), new VoiceSessionController.UtteranceRequest("громче", "ru"));

        assertEquals(200, response.getStatusCode().value());
        VoiceSession stored = registry.get(session.getSessionId()).orElseThrow();
        assertEquals("громче", stored.getTranscript());
        assertEquals("VOLUME_UP", stored.getIntent());
        assertEquals(0.9, stored.getIntentConfidence());
        assertEquals("regex", stored.getIntentSource());
        assertEquals(VoiceSessionStatus.COMPLETED, stored.getStatus());
        assertEquals("cmd-1", stored.getCommandId());
        assertEquals("corr-x", stored.getCorrelationId());
        assertEquals("Готово", stored.getReplyText());
    }

    @Test
    void utteranceMarksRealSessionFailedWhenIntentUnresolved() {
        VoiceSession session = registry.start("agent-1", "user-1");
        when(intents.resolve("абракадабра", "ru")).thenReturn(IntentResolver.Resolution.empty());

        controller.utterance(session.getSessionId(), new VoiceSessionController.UtteranceRequest("абракадабра", "ru"));

        VoiceSession stored = registry.get(session.getSessionId()).orElseThrow();
        assertEquals(VoiceSessionStatus.FAILED, stored.getStatus());
        assertEquals("абракадабра", stored.getTranscript());
    }

    @Test
    void utteranceHandlesReplyWithNullFeedbackGracefully() {
        VoiceSession session = registry.start("agent-1", "user-1");
        when(intents.resolve("громче", "ru")).thenReturn(new IntentResolver.Resolution("VOLUME_UP", "regex", 0.9));
        OrchestratorVoiceClient.VoiceLoopReply reply = new OrchestratorVoiceClient.VoiceLoopReply(
                null, null, VoiceSessionStatus.FAILED, null);
        when(orchestratorClient.dispatch(any(), any(), any(), any(), any())).thenReturn(reply);

        controller.utterance(session.getSessionId(), new VoiceSessionController.UtteranceRequest("громче", "ru"));

        VoiceSession stored = registry.get(session.getSessionId()).orElseThrow();
        assertEquals(VoiceSessionStatus.FAILED, stored.getStatus());
        assertEquals(null, stored.getReplyText());
    }
}
