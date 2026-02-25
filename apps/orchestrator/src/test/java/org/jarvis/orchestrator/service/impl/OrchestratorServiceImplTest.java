package org.jarvis.orchestrator.service.impl;

import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.LlmChatResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceImplTest {

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

    private OrchestratorServiceImpl service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownLlmExecutor();
        }
    }

    @Test
    void shouldFallbackWhenLlmExecutorQueueIsFullWithoutCrashing() throws Exception {
        OrchestratorExecutorProperties props = new OrchestratorExecutorProperties();
        props.setCorePoolSize(1);
        props.setMaxPoolSize(1);
        props.setQueueCapacity(1);
        props.setKeepAliveSeconds(30);
        props.setShutdownAwaitSeconds(2);

        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                props);

        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);

        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), any(Language.class)))
                .thenReturn("fallback");

        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseLlmCalls = new CountDownLatch(1);

        when(llmClient.chat(any(), anyString())).thenAnswer(invocation -> {
            firstCallStarted.countDown();
            releaseLlmCalls.await(3, TimeUnit.SECONDS);
            return new LlmChatResponse("llm-reply", Map.of(), "test-model", 1, "neutral");
        });

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(
                    () -> service.executeIntent("unknown", Map.of(), "ru", "corr-1", "cmd-1"));
            assertTrue(firstCallStarted.await(1, TimeUnit.SECONDS));

            Future<String> second = callers.submit(
                    () -> service.executeIntent("unknown", Map.of(), "ru", "corr-2", "cmd-2"));
            Thread.sleep(150);

            String third = assertDoesNotThrow(
                    () -> service.executeIntent("unknown", Map.of(), "ru", "corr-3", "cmd-3"));

            assertEquals("fallback", third);
            assertEquals(1L, service.getRejectedLlmTasksCount());

            releaseLlmCalls.countDown();
            assertDoesNotThrow(() -> first.get(3, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> second.get(3, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void shouldShutdownExecutorGracefully() {
        OrchestratorExecutorProperties props = new OrchestratorExecutorProperties();
        service = new OrchestratorServiceImpl(
                nlpClient,
                pcControlClient,
                apiGatewayPcClient,
                phraseProvider,
                llmClient,
                props);

        assertFalse(service.getLlmExecutor().isShutdown());
        service.shutdownLlmExecutor();
        assertTrue(service.getLlmExecutor().isShutdown());
    }
}
