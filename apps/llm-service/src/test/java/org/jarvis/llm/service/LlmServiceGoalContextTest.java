package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.client.UserProfileClient;
import org.jarvis.llm.config.LlmBackgroundExecutor;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmServiceGoalContextTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private LlmConversationMemory conversationMemory;
    @Mock
    private UserProfileClient userProfileClient;
    @Mock
    private PersonalizedPromptBuilder promptBuilder;
    @Mock
    private EmotionSelector emotionSelector;
    @Mock
    private RussianLanguageEnforcer languageEnforcer;
    @Mock
    private LlmBackgroundExecutor backgroundExecutor;
    @Mock
    private MemoryClient memoryClient;
    @Mock
    private TokenBudgetManager tokenBudgetManager;

    private LlmService llmService;

    @BeforeEach
    void setUp() {
        llmService = new LlmService(
                llmClient,
                conversationMemory,
                userProfileClient,
                promptBuilder,
                emotionSelector,
                languageEnforcer,
                backgroundExecutor,
                memoryClient,
                tokenBudgetManager);
        ReflectionTestUtils.setField(llmService, "memoryEnabled", false);
    }

    @Test
    void processMessageBuildsPromptWithGoalsFromUserProfile() {
        UserPreferencesDto preferences = preferences();
        when(userProfileClient.getPreferences("user", "corr-1")).thenReturn(preferences);
        when(userProfileClient.getGoals("user", "corr-1")).thenReturn(List.of("finish Jarvis", "ship MVP"));
        when(promptBuilder.buildSystemPrompt(
                "Anna",
                "Europe/Warsaw",
                "engineer",
                List.of("finish Jarvis", "ship MVP"),
                CommunicationStyle.FRIENDLY,
                true)).thenReturn("system prompt");
        when(conversationMemory.getHistory("user-session")).thenReturn(List.of());
        when(tokenBudgetManager.buildMessages("system prompt", "", List.of(), "Привет")).thenReturn(List.of(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system prompt"),
                new ChatMessageDto(ChatMessageDto.Role.USER, "Привет")));
        when(llmClient.chat(List.of(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system prompt"),
                new ChatMessageDto(ChatMessageDto.Role.USER, "Привет")), 512, 0.7, "corr-1"))
                .thenReturn(new ChatResponseDto("Готово", null, "test-model", 10, Emotion.NEUTRAL));
        when(emotionSelector.selectEmotion(eq("Привет"), any(), eq(CommunicationStyle.FRIENDLY)))
                .thenReturn(Emotion.NEUTRAL);

        llmService.processMessage("user-session", "Привет", "corr-1");

        verify(userProfileClient).getGoals("user", "corr-1");
        verify(promptBuilder).buildSystemPrompt(
                eq("Anna"),
                eq("Europe/Warsaw"),
                eq("engineer"),
                eq(List.of("finish Jarvis", "ship MVP")),
                eq(CommunicationStyle.FRIENDLY),
                eq(true));
    }

    @Test
    void processMessageUsesExplicitUserIdWhenProvided() {
        UserPreferencesDto preferences = preferences();
        when(userProfileClient.getPreferences("user-42", "corr-2")).thenReturn(preferences);
        when(userProfileClient.getGoals("user-42", "corr-2")).thenReturn(List.of("runtime goal"));
        when(promptBuilder.buildSystemPrompt(
                "Anna",
                "Europe/Warsaw",
                "engineer",
                List.of("runtime goal"),
                CommunicationStyle.FRIENDLY,
                true)).thenReturn("system prompt");
        when(conversationMemory.getHistory("corr-2")).thenReturn(List.of());
        when(tokenBudgetManager.buildMessages("system prompt", "", List.of(), "Напомни цель")).thenReturn(List.of(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system prompt"),
                new ChatMessageDto(ChatMessageDto.Role.USER, "Напомни цель")));
        when(llmClient.chat(List.of(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system prompt"),
                new ChatMessageDto(ChatMessageDto.Role.USER, "Напомни цель")), 512, 0.7, "corr-2"))
                .thenReturn(new ChatResponseDto("Готово", null, "test-model", 10, Emotion.NEUTRAL));
        when(emotionSelector.selectEmotion(eq("Напомни цель"), any(), eq(CommunicationStyle.FRIENDLY)))
                .thenReturn(Emotion.NEUTRAL);

        llmService.processMessage("corr-2", "user-42", "Напомни цель", "corr-2");

        verify(userProfileClient).getPreferences("user-42", "corr-2");
        verify(userProfileClient).getGoals("user-42", "corr-2");
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
