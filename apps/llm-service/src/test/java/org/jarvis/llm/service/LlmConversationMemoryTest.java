package org.jarvis.llm.service;

import org.jarvis.llm.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConversationMemoryTest {

    @Test
    void shouldTrimHistoryKeepingSystemMessageAndLatestItems() {
        LlmConversationMemory memory = new LlmConversationMemory();
        ReflectionTestUtils.setField(memory, "maxHistoryLength", 3);

        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "system"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "u1"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.ASSISTANT, "a1"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "u2"));

        List<ChatMessageDto> history = memory.getHistory("s1");
        assertTrue(history.size() <= 3);
        assertTrue(history.stream().anyMatch(m -> m.getRole() == ChatMessageDto.Role.SYSTEM));
        assertTrue(history.stream().anyMatch(m -> "u2".equals(m.getContent())));
    }

    @Test
    void shouldHandleConcurrentWritesWithoutOverflow() throws InterruptedException {
        LlmConversationMemory memory = new LlmConversationMemory();
        ReflectionTestUtils.setField(memory, "maxHistoryLength", 50);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int index = i;
            tasks.add(() -> memory.addMessage("session-concurrent",
                    new ChatMessageDto(ChatMessageDto.Role.USER, "m" + index)));
        }
        for (Runnable task : tasks) {
            executor.submit(task);
        }
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        List<ChatMessageDto> history = memory.getHistory("session-concurrent");
        assertTrue(finished);
        assertTrue(history.size() <= 50);
    }
}
