package org.jarvis.orchestrator.service.impl;

import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.LlmChatResponse;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorLlmUserContextTest {

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

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownLlmExecutor();
        }
    }

    @Test
    void fallbackLlmCallPropagatesDelegatedUserId() {
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                smartHomeClient,
                new OrchestratorExecutorProperties());
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);

        when(nlpClient.analyze(new NlpClient.AnalyzeRequest("какие у меня цели"))).thenReturn(
                new NlpClient.NlpResult("fallback", java.util.Map.of()));
        when(llmClient.chat(org.mockito.ArgumentMatchers.any(), eq("corr-1"), eq("user-42")))
                .thenReturn(new LlmChatResponse("llm-reply", java.util.Map.of(), "stub", 1, "NEUTRAL"));

        String reply = service.processText("какие у меня цели", "ru", "corr-1", "user-42");

        assertEquals("llm-reply", reply);
        ArgumentCaptor<org.jarvis.orchestrator.dto.LlmChatRequest> requestCaptor =
                ArgumentCaptor.forClass(org.jarvis.orchestrator.dto.LlmChatRequest.class);
        verify(llmClient).chat(requestCaptor.capture(), eq("corr-1"), eq("user-42"));
        assertEquals("user-42-corr-1", requestCaptor.getValue().sessionId());
        assertEquals("какие у меня цели", requestCaptor.getValue().messages().get(0).content());
    }
}
