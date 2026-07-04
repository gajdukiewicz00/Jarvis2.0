package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.client.UserProfileClient;
import org.jarvis.llm.config.LlmBackgroundExecutor;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.jarvis.llm.safety.UntrustedTextGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Service-level contention verification that goes through the real
 * LlmService.processMessage() → LlmAdmissionController path.
 *
 * Unlike the pure semaphore-level tests in LlmAdmissionControllerTest,
 * this exercises profile resolution, priority mapping, and admission
 * control within the actual service method.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmServiceContentionIntegrationTest {

    @Mock private LlmClient llmClient;
    @Mock private LlmConversationMemory conversationMemory;
    @Mock private UserProfileClient userProfileClient;
    @Mock private PersonalizedPromptBuilder promptBuilder;
    @Mock private EmotionSelector emotionSelector;
    @Mock private RussianLanguageEnforcer languageEnforcer;
    @Mock private LlmBackgroundExecutor backgroundExecutor;
    @Mock private MemoryClient memoryClient;
    @Mock private TokenBudgetManager tokenBudgetManager;
    @Mock private LlmMetrics llmMetrics;

    private LlmAdmissionController admissionController;
    private LlmService llmService;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() {
        admissionController = new LlmAdmissionController(1, 4);
        ModelProfileProperties profileProperties = new ModelProfileProperties();

        llmService = new LlmService(
                llmClient, conversationMemory, userProfileClient, promptBuilder,
                emotionSelector, languageEnforcer, backgroundExecutor,
                admissionController, profileProperties, llmMetrics,
                memoryClient, tokenBudgetManager,
                new UntrustedTextGuard());
        ReflectionTestUtils.setField(llmService, "memoryEnabled", false);

        testExecutor = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        testExecutor.shutdownNow();
    }

    @Test
    void backgroundRejectedWhileVoiceHoldsInferenceSlot() throws Exception {
        CountDownLatch voiceInferenceStarted = new CountDownLatch(1);
        CountDownLatch releaseVoice = new CountDownLatch(1);

        stubCollaboratorsForUser("voice-user");
        stubCollaboratorsForUser("bg-user");

        when(llmClient.chat(anyList(), eq(256), eq(0.3), eq("voice-corr")))
                .thenAnswer(inv -> {
                    voiceInferenceStarted.countDown();
                    releaseVoice.await(5, TimeUnit.SECONDS);
                    return new ChatResponseDto("voice-reply", null, "model", 100, Emotion.NEUTRAL);
                });

        Future<ChatResponseDto> voiceFuture = testExecutor.submit(() ->
                llmService.processMessage("voice-session", "voice-user", "Привет",
                        "voice-corr", false, "voice-fast"));

        assertTrue(voiceInferenceStarted.await(3, TimeUnit.SECONDS),
                "Voice inference should start within timeout");

        assertEquals(1, admissionController.getActiveInferences(),
                "Voice should hold the single inference permit");

        AtomicReference<Exception> bgException = new AtomicReference<>();
        Future<?> bgFuture = testExecutor.submit(() -> {
            try {
                llmService.processMessage("bg-session", "bg-user", "Summarize",
                        "bg-corr", false, "background-summary");
            } catch (RuntimeException e) {
                bgException.set(e);
            }
        });

        bgFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(bgException.get(),
                "Background should be rejected while voice holds the permit");
        assertTrue(bgException.get().getMessage().contains("admission control"),
                "Rejection message should mention admission control");
        assertTrue(admissionController.getRejectedCount() > 0,
                "Admission metrics should reflect the rejection");

        releaseVoice.countDown();
        ChatResponseDto voiceResult = voiceFuture.get(3, TimeUnit.SECONDS);
        assertEquals("voice-reply", voiceResult.getReply());

        assertEquals(0, admissionController.getActiveInferences(),
                "Permit should be released after voice completes");
    }

    @Test
    void voiceAcquiresAfterInteractiveReleases() throws Exception {
        CountDownLatch desktopStarted = new CountDownLatch(1);
        CountDownLatch releaseDesktop = new CountDownLatch(1);

        stubCollaboratorsForUser("desktop-user");
        stubCollaboratorsForUser("voice-user");

        when(llmClient.chat(anyList(), eq(512), eq(0.7), eq("desktop-corr")))
                .thenAnswer(inv -> {
                    desktopStarted.countDown();
                    releaseDesktop.await(5, TimeUnit.SECONDS);
                    return new ChatResponseDto("desktop-reply", null, "model", 200, Emotion.NEUTRAL);
                });
        when(llmClient.chat(anyList(), eq(256), eq(0.3), eq("voice-corr")))
                .thenReturn(new ChatResponseDto("voice-reply", null, "model", 50, Emotion.NEUTRAL));

        Future<ChatResponseDto> desktopFuture = testExecutor.submit(() ->
                llmService.processMessage("desktop-session", "desktop-user", "Привет",
                        "desktop-corr", false, "desktop-general"));

        assertTrue(desktopStarted.await(3, TimeUnit.SECONDS));

        Future<ChatResponseDto> voiceFuture = testExecutor.submit(() ->
                llmService.processMessage("voice-session", "voice-user", "Привет",
                        "voice-corr", false, "voice-fast"));

        Thread.sleep(200);
        assertEquals(1, admissionController.getActiveInferences(),
                "Only one concurrent inference should be active");

        releaseDesktop.countDown();
        desktopFuture.get(3, TimeUnit.SECONDS);
        ChatResponseDto voiceResult = voiceFuture.get(5, TimeUnit.SECONDS);
        assertEquals("voice-reply", voiceResult.getReply(),
                "Voice should eventually acquire after desktop releases");
        assertEquals(2, admissionController.getTotalAdmitted());
    }

    @Test
    void admissionMetricsAccurateAfterMixedContentionScenario() throws Exception {
        stubCollaboratorsForUser("user-a");

        when(llmClient.chat(anyList(), anyInt(), anyDouble(), anyString()))
                .thenReturn(new ChatResponseDto("ok", null, "model", 10, Emotion.NEUTRAL));

        llmService.processMessage("s1", "user-a", "Msg1", "c1", false, "desktop-general");
        assertEquals(1, admissionController.getTotalAdmitted());
        assertEquals(0, admissionController.getRejectedCount());
        assertEquals(0, admissionController.getActiveInferences());

        long admitted = admissionController.getTotalAdmitted();
        long rejected = admissionController.getRejectedCount();
        assertTrue(admitted > 0, "Should have at least one admitted request");
        assertEquals(0, rejected, "No rejections expected in non-contended scenario");
    }

    private void stubCollaboratorsForUser(String userId) {
        UserPreferencesDto prefs = new UserPreferencesDto();
        prefs.setFullName("Test");
        prefs.setTimezone("UTC");
        prefs.setOccupation("tester");
        prefs.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        prefs.setAllowSarcasm(false);

        when(userProfileClient.getPreferences(eq(userId), anyString())).thenReturn(prefs);
        when(userProfileClient.getGoals(eq(userId), anyString())).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(eq("Test"), eq("UTC"), eq("tester"),
                eq(List.of()), eq(CommunicationStyle.FRIENDLY), eq(false)))
                .thenReturn("system prompt");
        when(conversationMemory.getHistory(anyString())).thenReturn(List.of());
        when(tokenBudgetManager.buildMessages(eq("system prompt"), eq(""), anyList(), anyString()))
                .thenReturn(List.of(
                        new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system prompt"),
                        new ChatMessageDto(ChatMessageDto.Role.USER, "test")));
        when(emotionSelector.selectEmotion(anyString(), any(), any())).thenReturn(Emotion.NEUTRAL);
    }
}
