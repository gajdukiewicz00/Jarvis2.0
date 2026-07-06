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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Finding #31: memory-service RAG lookup must be best-effort. If
 * memoryClient.searchContext(...) throws (memory-service down/timeout/5xx), the chat
 * turn must still complete using an LLM reply, instead of the whole request failing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmServiceMemoryDegradationTest {

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
        ReflectionTestUtils.setField(llmService, "memoryEnabled", true);

        when(userProfileClient.getPreferences(anyString(), anyString())).thenReturn(preferences());
        when(userProfileClient.getGoals(anyString(), anyString())).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("system prompt");
        when(conversationMemory.getHistory(anyString())).thenReturn(List.of());
        when(tokenBudgetManager.buildMessages(anyString(), anyString(), any(), anyString()))
                .thenReturn(List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "Привет")));
        when(llmClient.chat(any(), anyInt(), anyDouble(), anyString()))
                .thenReturn(new ChatResponseDto("Готово", null, "test-model", 10, Emotion.NEUTRAL));
        when(emotionSelector.selectEmotion(anyString(), any(), any())).thenReturn(Emotion.NEUTRAL);
    }

    @Test
    void processMessageDegradesGracefullyWhenMemoryServiceIsDown() {
        when(memoryClient.searchContext(anyString(), anyString(), anyInt(), anyInt(),
                anyBoolean(), anyBoolean(), anyString()))
                .thenThrow(new MemoryClient.MemoryClientException("Memory search failed: connection refused"));

        // Without the fix, MemoryClientException propagates uncaught out of processMessage()
        // and the whole chat turn fails even though RAG memory is meant to be best-effort.
        ChatResponseDto response = llmService.processMessage("user-session", "Привет", "corr-down");

        assertThat(response).isNotNull();
        assertThat(response.getReply()).isEqualTo("Готово");
    }

    private UserPreferencesDto preferences() {
        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setFullName("Anna");
        dto.setTimezone("Europe/Warsaw");
        dto.setOccupation("engineer");
        dto.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        dto.setAllowSarcasm(true);
        return dto;
    }
}
