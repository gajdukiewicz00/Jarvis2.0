package org.jarvis.orchestrator.service.impl;

import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.jarvis.orchestrator.phrases.PhraseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resilience behavior for the nlp-service call in {@link OrchestratorServiceImpl#processText}:
 * bounded retry with backoff, circuit-breaker-open short-circuiting, and graceful
 * degradation (fallback response instead of a propagated 500) when nlp-service is down.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorNlpResilienceTest {

    @Mock
    private NlpClient nlpClient;
    @Mock
    private PcControlClient pcControlClient;
    @Mock
    private ApiGatewayPcClient apiGatewayPcClient;
    @Mock
    private JarvisPhraseProvider phraseProvider;
    @Mock
    private LlmServiceClient llmClient;
    @Mock
    private SmartHomeClient smartHomeClient;

    private OrchestratorServiceImpl service;

    private OrchestratorServiceImpl newService() {
        service = new OrchestratorServiceImpl(
                nlpClient, pcControlClient, apiGatewayPcClient, phraseProvider,
                llmClient, smartHomeClient, new OrchestratorExecutorProperties());
        ReflectionTestUtils.setField(service, "nlpRetryInitialBackoffMs", 1L);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownLlmExecutor();
        }
    }

    @Test
    void successfulAnalyzeRoutesToIntentNormally() {
        newService();
        when(nlpClient.analyze(new NlpClient.AnalyzeRequest("turn up volume")))
                .thenReturn(new NlpClient.NlpResult("volume_up", Map.of()));
        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_UP), eq(Language.RU))).thenReturn("louder");

        String result = service.processText("turn up volume", "ru", "corr-1", "user-1");

        assertEquals("louder", result);
        verify(nlpClient, times(1)).analyze(any());
    }

    @Test
    void analyzeRetriesOnTransientFailureThenSucceeds() {
        newService();
        ReflectionTestUtils.setField(service, "nlpRetryMaxAttempts", 3);
        when(nlpClient.analyze(any()))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(new NlpClient.NlpResult("mute", Map.of()));
        when(phraseProvider.getPhrase(eq(PhraseContext.MUTE), eq(Language.RU))).thenReturn("muted");

        String result = service.processText("be quiet", "ru", "corr-2", "user-1");

        assertEquals("muted", result);
        verify(nlpClient, times(2)).analyze(any());
    }

    @Test
    void analyzeFailureDegradesToFallbackAndMarksExecutionFailed() {
        newService();
        ReflectionTestUtils.setField(service, "nlpRetryMaxAttempts", 2);
        when(nlpClient.analyze(any())).thenThrow(new RuntimeException("nlp-service down"));
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        IntentExecutionResult result = service.processTextDetailed("hello there", "ru", "corr-3", "user-1");

        assertEquals("dunno", result.responseText());
        assertTrue(result.executionFailed());
        assertTrue(result.failureReason().contains("nlp_unavailable"));
        // maxAttempts=2 -> exactly one retry before giving up.
        verify(nlpClient, times(2)).analyze(any());
    }

    @Test
    void circuitBreakerOpensAfterConsecutiveFailuresAndSkipsAnalyzeEntirely() {
        newService();
        ReflectionTestUtils.setField(service, "nlpRetryMaxAttempts", 1);
        ReflectionTestUtils.setField(service, "nlpCircuitBreakerFailureThreshold", 1);
        ReflectionTestUtils.setField(service, "nlpCircuitBreakerResetTimeoutSeconds", 60);
        when(nlpClient.analyze(any())).thenThrow(new RuntimeException("nlp-service down"));
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        IntentExecutionResult first = service.processTextDetailed("hello", "ru", "corr-4a", "user-1");
        IntentExecutionResult second = service.processTextDetailed("hello", "ru", "corr-4b", "user-1");

        assertTrue(first.executionFailed());
        assertTrue(second.executionFailed());
        assertEquals("dunno", second.responseText());
        // Breaker opened after the first failure; the second call must not reach nlp-service at all.
        verify(nlpClient, times(1)).analyze(any());
    }

    @Test
    void recoversAfterHalfOpenTrialSucceeds() throws InterruptedException {
        newService();
        ReflectionTestUtils.setField(service, "nlpRetryMaxAttempts", 1);
        ReflectionTestUtils.setField(service, "nlpCircuitBreakerFailureThreshold", 1);
        ReflectionTestUtils.setField(service, "nlpCircuitBreakerResetTimeoutSeconds", 0);
        when(nlpClient.analyze(any()))
                .thenThrow(new RuntimeException("down"))
                .thenReturn(new NlpClient.NlpResult("hello", Map.of()));
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");
        when(phraseProvider.getPhrase(eq(PhraseContext.GREETING), eq(Language.RU))).thenReturn("hi there");

        IntentExecutionResult first = service.processTextDetailed("hello", "ru", "corr-5a", "user-1");
        assertTrue(first.executionFailed());

        Thread.sleep(5); // reset timeout is 0s, but allow the clock to move past it deterministically
        IntentExecutionResult second = service.processTextDetailed("hello", "ru", "corr-5b", "user-1");

        assertFalse(second.executionFailed());
        assertEquals("hi there", second.responseText());
        verify(nlpClient, times(2)).analyze(any());
    }
}
