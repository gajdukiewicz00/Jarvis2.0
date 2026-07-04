package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.client.UserProfileClient;
import org.jarvis.llm.config.LlmBackgroundExecutor;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.DialogRequest;
import org.jarvis.llm.dto.DialogResponse;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.jarvis.llm.safety.UntrustedTextGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmServiceDialogTest {

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

    @BeforeEach
    void setUp() {
        admissionController = new LlmAdmissionController(1, 4);
        ModelProfileProperties profileProperties = new ModelProfileProperties();
        llmService = new LlmService(
                llmClient, conversationMemory, userProfileClient, promptBuilder,
                emotionSelector, languageEnforcer, backgroundExecutor,
                admissionController, profileProperties, llmMetrics,
                memoryClient, tokenBudgetManager, new UntrustedTextGuard());
        ReflectionTestUtils.setField(llmService, "memoryEnabled", false);
        when(userProfileClient.getPreferences(anyString(), anyString())).thenReturn(preferences());
    }

    private UserPreferencesDto preferences() {
        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setFullName("Denis");
        dto.setTimezone("Europe/Warsaw");
        dto.setOccupation("engineer");
        dto.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        dto.setAllowSarcasm(false);
        return dto;
    }

    @Test
    void exitPhraseInRussianEndsDialogWithoutCallingLlm() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("s1");
        request.setUserId("u1");
        request.setInput("хватит, выйди из диалога");
        request.setLang("ru");

        DialogResponse response = llmService.processDialog(request, "corr-1");

        assertThat(response.isShouldContinue()).isFalse();
        assertThat(response.getMode()).isEqualTo("command");
        assertThat(response.getReply()).contains("Понял");
        org.mockito.Mockito.verifyNoInteractions(llmClient);
    }

    @Test
    void exitPhraseInEnglishReturnsEnglishReply() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("s1");
        request.setUserId("u1");
        request.setInput("please end dialog now");
        request.setLang("en");

        DialogResponse response = llmService.processDialog(request, "corr-2");

        assertThat(response.isShouldContinue()).isFalse();
        assertThat(response.getReply()).contains("Understood");
    }

    @Test
    void rateLimitExceededReturnsThrottledReplyWithoutCallingLlm() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("s1");
        request.setUserId("rate-limited-user");
        request.setInput("Привет");

        // First call establishes lastRequestTime for this user.
        stubHappyPath();
        llmService.processDialog(request, "corr-3a");

        // Second call immediately after should be rate limited.
        DialogResponse response = llmService.processDialog(request, "corr-3b");

        assertThat(response.isShouldContinue()).isTrue();
        assertThat(response.getMode()).isEqualTo("dialog");
        assertThat(response.getReply()).contains("Слишком много запросов");
    }

    @Test
    void successfulDialogReturnsLlmReplyAndEmotion() {
        stubHappyPath();
        DialogRequest request = new DialogRequest();
        request.setSessionId("s-success");
        request.setUserId("user-success");
        request.setInput("Как дела?");
        request.setContext(Map.of("profile", "vip", "planner", "trip", "analytics", "stats"));

        DialogResponse response = llmService.processDialog(request, "corr-4");

        assertThat(response.getReply()).isEqualTo("Отлично, сэр.");
        assertThat(response.isShouldContinue()).isTrue();
        assertThat(response.getMode()).isEqualTo("dialog");
        assertThat(response.getConfidence()).isEqualTo(0.9);
        verify(conversationMemory, times(2)).addMessage(anyString(), any());
    }

    @Test
    void dialogFallsBackToExtractedUserIdWhenUserIdMissing() {
        stubHappyPath();
        DialogRequest request = new DialogRequest();
        request.setSessionId("derived-session-id");
        request.setInput("Привет");

        llmService.processDialog(request, "corr-5");

        verify(userProfileClient).getPreferences("derived", "corr-5");
    }

    @Test
    void exceptionDuringDialogReturnsFallbackReply() {
        when(llmClient.chat(any(), any(), any(), anyString())).thenThrow(new RuntimeException("model exploded"));

        DialogRequest request = new DialogRequest();
        request.setSessionId("s-err");
        request.setUserId("user-err");
        request.setInput("Привет");

        DialogResponse response = llmService.processDialog(request, "corr-6");

        assertThat(response.isShouldContinue()).isTrue();
        assertThat(response.getMode()).isEqualTo("dialog");
        assertThat(response.getReply()).contains("ошибка");
        assertThat(response.getEmotion()).isEqualTo(Emotion.NEUTRAL);
    }

    @Test
    void longInputIsTruncatedBeforeProcessing() {
        stubHappyPath();
        String longInput = "a".repeat(3000);
        DialogRequest request = new DialogRequest();
        request.setSessionId("s-trunc");
        request.setUserId("user-trunc");
        request.setInput(longInput);

        llmService.processDialog(request, "corr-7");

        verify(llmClient).chat(any(), any(), any(), anyString());
    }

    @Test
    void clearSessionDelegatesToConversationMemory() {
        llmService.clearSession("session-x");

        verify(conversationMemory).clearSession("session-x");
    }

    @Test
    void isAvailableTrueWhenLlmHealthyAndMemoryDisabled() {
        ReflectionTestUtils.setField(llmService, "memoryEnabled", false);
        when(llmClient.isHealthy()).thenReturn(true);

        assertThat(llmService.isAvailable()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(memoryClient);
    }

    @Test
    void isAvailableFalseWhenLlmUnhealthy() {
        ReflectionTestUtils.setField(llmService, "memoryEnabled", false);
        when(llmClient.isHealthy()).thenReturn(false);

        assertThat(llmService.isAvailable()).isFalse();
    }

    @Test
    void isAvailableFalseWhenMemoryEnabledButUnhealthy() {
        ReflectionTestUtils.setField(llmService, "memoryEnabled", true);
        when(llmClient.isHealthy()).thenReturn(true);
        when(memoryClient.isHealthy()).thenReturn(false);

        assertThat(llmService.isAvailable()).isFalse();
    }

    @Test
    void isAvailableTrueWhenLlmAndMemoryHealthy() {
        ReflectionTestUtils.setField(llmService, "memoryEnabled", true);
        when(llmClient.isHealthy()).thenReturn(true);
        when(memoryClient.isHealthy()).thenReturn(true);

        assertThat(llmService.isAvailable()).isTrue();
    }

    private void stubHappyPath() {
        when(llmClient.chat(any(), any(), any(), anyString()))
                .thenReturn(new ChatResponseDto("Отлично, сэр.", null, "model", 5, Emotion.NEUTRAL));
        when(emotionSelector.selectEmotion(anyString(), any(), any())).thenReturn(Emotion.NEUTRAL);
        when(conversationMemory.getHistory(anyString())).thenReturn(List.of());
    }
}
