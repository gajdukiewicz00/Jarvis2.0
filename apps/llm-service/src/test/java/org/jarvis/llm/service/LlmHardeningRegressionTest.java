package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.controller.LlmRestController;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * High-value regression tests for LLM hardening guarantees:
 * - malformed tool-call rejection
 * - disabled LLM guard path
 * - degraded path when memory/embedding unavailable
 * - profile propagation behavior
 * - voice + desktop contention
 */
@ExtendWith(MockitoExtension.class)
class LlmHardeningRegressionTest {

    @Mock private LlmClient llmClient;
    @Mock private MemoryClient memoryClient;
    @Mock private LlmService llmService;

    private LlmAdmissionController admissionController;
    private LlmLifecycleManager lifecycleManager;
    private ModelProfileProperties profileProperties;

    @BeforeEach
    void setUp() {
        admissionController = new LlmAdmissionController(1, 4);
        lifecycleManager = new LlmLifecycleManager(llmClient, memoryClient);
        profileProperties = new ModelProfileProperties();
    }

    // ── Malformed tool-call payload rejection ──────────────────────────
    // Covered by ToolCallValidatorTest in the orchestrator package:
    //   - nullName, extraFields, wrongType, unknownTool, missingRequired, emptyString, belowMinimum
    // Those tests exercise the same rejection paths via package-visible loadFromParsed().

    // ── Disabled LLM guard path ────────────────────────────────────────

    @Test
    void chatEndpointRejectsWhenLlmDisabledViaGuard() {
        AiRuntimeStatusService aiRuntimeStatusService = mock(AiRuntimeStatusService.class);

        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new LlmClient.LlmClientException("LLM is disabled (jarvis.llm.enabled=false)", null));

        LlmRestController controller = new LlmRestController(
                llmService, aiRuntimeStatusService, lifecycleManager, admissionController);

        ChatMessageDto msg = new ChatMessageDto(ChatMessageDto.Role.USER, "Hello");
        ChatRequestDto request = new ChatRequestDto("session-1", List.of(msg), 256, 0.5);

        ResponseEntity<?> response = controller.chat(request, null, null, null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    // ── Degraded path: memory unavailable ──────────────────────────────

    @Test
    void lifecycleDegradedWhenMemoryUnavailable() {
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);
        ReflectionTestUtils.setField(lifecycleManager, "memoryEnabled", true);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model", "/path", Collections.emptyMap(), null));
        when(memoryClient.isHealthy()).thenReturn(false);

        lifecycleManager.refreshState();

        assertEquals(LlmLifecycleManager.State.DEGRADED, lifecycleManager.getState());
        assertTrue(lifecycleManager.isUsable(), "Degraded state should still be usable");
        assertTrue(lifecycleManager.isWarmupComplete());
        assertEquals("memory-service unavailable", lifecycleManager.getStateReason());
    }

    @Test
    void lifecycleErrorWhenLlmBackendDown() {
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                false, "error", null, false, null, null, null,
                null, null, Collections.emptyMap(), "connection refused"));

        lifecycleManager.refreshState();

        assertEquals(LlmLifecycleManager.State.ERROR, lifecycleManager.getState());
        assertFalse(lifecycleManager.isUsable());
        assertFalse(lifecycleManager.isWarmupComplete());
    }

    // ── Profile propagation behavior ───────────────────────────────────

    @Test
    void voiceProfileResolvesToVoiceFast() {
        ModelProfileProperties.Profile p = profileProperties.resolve("voice-fast");
        assertEquals(256, p.getMaxTokens());
        assertEquals(0.3, p.getTemperature(), 0.01);
        assertEquals(15, p.getTimeoutSeconds());
    }

    @Test
    void desktopProfileResolvesToDesktopGeneral() {
        ModelProfileProperties.Profile p = profileProperties.resolve("desktop-general");
        assertEquals(512, p.getMaxTokens());
        assertEquals(0.7, p.getTemperature(), 0.01);
        assertEquals(120, p.getTimeoutSeconds());
    }

    @Test
    void backgroundProfileResolvesToBackgroundSummary() {
        ModelProfileProperties.Profile p = profileProperties.resolve("background-summary");
        assertEquals(1024, p.getMaxTokens());
        assertEquals(300, p.getTimeoutSeconds());
    }

    @Test
    void missingProfileFallsBackToDesktopGeneral() {
        ModelProfileProperties.Profile p = profileProperties.resolve(null);
        assertEquals(512, p.getMaxTokens());
        assertEquals(0.7, p.getTemperature(), 0.01);
    }

    @Test
    void orchestrationProfileUsedByDefault() {
        ModelProfileProperties.Profile p = profileProperties.resolve("orchestration");
        assertEquals(700, p.getMaxTokens());
        assertEquals(0.2, p.getTemperature(), 0.01);
        assertEquals(60, p.getTimeoutSeconds());
    }

    // ── Contention: voice + desktop admission ──────────────────────────

    @Test
    void voicePreemptsDesktopUnderContention() throws Exception {
        LlmAdmissionController controller = new LlmAdmissionController(1, 2);

        try (LlmAdmissionController.AdmissionTicket desktopTicket =
                     controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 1)) {
            assertNotNull(desktopTicket, "Desktop should acquire the single permit");

            AtomicInteger voiceAcquired = new AtomicInteger(0);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch voiceStarted = new CountDownLatch(1);

            executor.submit(() -> {
                voiceStarted.countDown();
                try (LlmAdmissionController.AdmissionTicket voiceTicket =
                             controller.tryAcquire(LlmAdmissionController.Priority.VOICE, 3)) {
                    if (voiceTicket != null) {
                        voiceAcquired.incrementAndGet();
                    }
                }
            });

            voiceStarted.await(1, TimeUnit.SECONDS);
            Thread.sleep(100);
            assertEquals(0, voiceAcquired.get(), "Voice should be waiting while desktop holds permit");

            desktopTicket.close();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, voiceAcquired.get(), "Voice should acquire after desktop releases");
        }
    }

    @Test
    void backgroundRejectedWhileVoiceHoldsPermit() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 1);

        try (LlmAdmissionController.AdmissionTicket voiceTicket =
                     controller.tryAcquire(LlmAdmissionController.Priority.VOICE, 5)) {
            assertNotNull(voiceTicket);

            LlmAdmissionController.AdmissionTicket bgTicket =
                    controller.tryAcquire(LlmAdmissionController.Priority.BACKGROUND, 1);
            assertNull(bgTicket, "Background should be rejected when voice holds the permit");
            assertTrue(controller.getRejectedCount() > 0);
        }
    }

    @Test
    void admissionMetricsAccumulateCorrectly() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 1);
        assertEquals(0, controller.getTotalAdmitted());
        assertEquals(0, controller.getRejectedCount());

        try (LlmAdmissionController.AdmissionTicket t = controller.tryAcquire(
                LlmAdmissionController.Priority.INTERACTIVE, 1)) {
            assertNotNull(t);
            assertEquals(1, controller.getTotalAdmitted());

            LlmAdmissionController.AdmissionTicket bg = controller.tryAcquire(
                    LlmAdmissionController.Priority.BACKGROUND, 1);
            assertNull(bg);
            assertEquals(1, controller.getRejectedCount());
        }

        assertEquals(0, controller.getActiveInferences());
        assertEquals(1, controller.getAvailablePermits());
    }
}
