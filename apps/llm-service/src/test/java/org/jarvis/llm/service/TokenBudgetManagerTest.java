package org.jarvis.llm.service;

import org.jarvis.llm.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetManagerTest {

    private TokenBudgetManager manager;

    @BeforeEach
    void setUp() {
        manager = new TokenBudgetManager();
        ReflectionTestUtils.setField(manager, "systemPromptBudget", 10);
        ReflectionTestUtils.setField(manager, "memoryContextBudget", 10);
        ReflectionTestUtils.setField(manager, "historyBudget", 10);
        ReflectionTestUtils.setField(manager, "totalBudget", 200);
    }

    @Test
    void buildMessagesIncludesMemoryContextWhenBudgetAllowsIt() {
        List<ChatMessageDto> messages = manager.buildMessages(
                "ssssssssssssssss",
                "mmmmmmmmmmmmmmmm",
                List.of(),
                "uuuuuuuu");

        assertEquals(2, messages.size());
        assertEquals(ChatMessageDto.Role.SYSTEM, messages.get(0).getRole());
        assertTrue(messages.get(0).getContent().contains("ПАМЯТЬ"));
        assertTrue(messages.get(0).getContent().contains("mmmmmmmmmmmmmmmm"));
        assertEquals(ChatMessageDto.Role.USER, messages.get(1).getRole());
    }

    @Test
    void buildMessagesKeepsMostRecentHistoryWhenBudgetIsTight() {
        List<ChatMessageDto> history = List.of(
                new ChatMessageDto(ChatMessageDto.Role.USER, "aaaaaaaaaaaaaaaa"),
                new ChatMessageDto(ChatMessageDto.Role.ASSISTANT, "bbbbbbbbbbbbbbbb"),
                new ChatMessageDto(ChatMessageDto.Role.USER, "cccccccccccccccc"),
                new ChatMessageDto(ChatMessageDto.Role.ASSISTANT, "dddddddddddddddd"));

        List<ChatMessageDto> messages = manager.buildMessages(
                "ssss",
                "",
                history,
                "uuuu");

        assertEquals(4, messages.size());
        assertEquals("cccccccccccccccc", messages.get(1).getContent());
        assertEquals("dddddddddddddddd", messages.get(2).getContent());
        assertEquals("uuuu", messages.get(3).getContent());
    }

    @Test
    void estimateTokensForMessagesAddsRoleOverhead() {
        int tokens = manager.estimateTokens(List.of(
                new ChatMessageDto(ChatMessageDto.Role.USER, "12345678"),
                new ChatMessageDto(ChatMessageDto.Role.ASSISTANT, "abcdefgh")));

        assertEquals(12, tokens);
    }

    @Test
    void getRemainingBudgetNeverDropsBelowHardMinimum() {
        assertEquals(100, manager.getRemainingBudget(500, 200));
        assertEquals(350, manager.getRemainingBudget(100, 500));
    }
}
