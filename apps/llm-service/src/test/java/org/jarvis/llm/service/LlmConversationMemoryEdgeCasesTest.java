package org.jarvis.llm.service;

import org.jarvis.llm.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConversationMemoryEdgeCasesTest {

    private static ChatMessageDto msg(String content) {
        return new ChatMessageDto(ChatMessageDto.Role.USER, content);
    }

    @Test
    void addMessageIgnoresNullOrBlankSessionOrNullMessage() {
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 10, null);

        memory.addMessage(null, msg("x"));
        memory.addMessage("", msg("x"));
        memory.addMessage("   ", msg("x"));
        memory.addMessage("valid", null);

        assertEquals(0, memory.getActiveSessionCount());
        assertTrue(memory.getHistory("valid").isEmpty());
    }

    @Test
    void getHistoryReturnsEmptyForNullOrBlankOrUnknownSession() {
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 10, null);

        assertTrue(memory.getHistory(null).isEmpty());
        assertTrue(memory.getHistory("").isEmpty());
        assertTrue(memory.getHistory("nope").isEmpty());
    }

    @Test
    void clearSessionRemovesHistoryAndIgnoresBlankIds() {
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 10, null);

        memory.addMessage("s1", msg("hello"));
        assertEquals(1, memory.getHistory("s1").size());

        memory.clearSession(null);
        memory.clearSession("");
        assertEquals(1, memory.getHistory("s1").size(), "blank ids must be no-ops");

        memory.clearSession("s1");
        assertTrue(memory.getHistory("s1").isEmpty());
    }

    @Test
    void activeSessionCountTracksDistinctSessions() {
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 10, null);

        memory.addMessage("a", msg("1"));
        memory.addMessage("b", msg("1"));
        memory.addMessage("a", msg("2"));

        assertEquals(2, memory.getActiveSessionCount());
    }

    @Test
    void lazilyBuildsCacheWhenNotConfiguredForTests() {
        // No configureForTests call -> exercises the lazy historyCache() init path.
        LlmConversationMemory memory = new LlmConversationMemory();

        memory.addMessage("lazy", msg("m1"));
        memory.addMessage("lazy", msg("m2"));

        assertEquals(2, memory.getHistory("lazy").size());
        assertEquals(1, memory.getActiveSessionCount());
    }

    @Test
    void fallsBackToDefaultsForNonPositiveOrZeroConfiguration() {
        // Zero TTL, non-positive maxSessions and maxMessages must fall back to safe defaults.
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ZERO, 0, 0, null);

        for (int i = 0; i < 5; i++) {
            memory.addMessage("s", msg("m" + i));
        }

        // Default max messages per session is 100, so all 5 are retained.
        assertEquals(5, memory.getHistory("s").size());
    }

    @Test
    void fallsBackToDefaultTtlForNegativeDuration() {
        LlmConversationMemory memory = new LlmConversationMemory();
        memory.configureForTests(Duration.ofSeconds(-5), 1_000, 10, null);

        memory.addMessage("neg", msg("m1"));
        assertEquals(1, memory.getHistory("neg").size());
    }
}
