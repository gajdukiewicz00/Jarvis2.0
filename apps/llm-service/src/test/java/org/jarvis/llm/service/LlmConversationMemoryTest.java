package org.jarvis.llm.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.jarvis.llm.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConversationMemoryTest {

    @Test
    void shouldEvictOldMessagesWhenSessionBufferLimitExceeded() {
        LlmConversationMemory memory = new LlmConversationMemory();
        MutableTicker ticker = new MutableTicker();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 3, ticker);

        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "m1"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "m2"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "m3"));
        memory.addMessage("s1", new ChatMessageDto(ChatMessageDto.Role.USER, "m4"));

        List<ChatMessageDto> history = memory.getHistory("s1");
        assertEquals(3, history.size());
        assertEquals(List.of("m2", "m3", "m4"), history.stream().map(ChatMessageDto::getContent).toList());
    }

    @Test
    void shouldEvictSessionAfterTtl() {
        LlmConversationMemory memory = new LlmConversationMemory();
        MutableTicker ticker = new MutableTicker();
        memory.configureForTests(Duration.ofSeconds(1), 1_000, 10, ticker);

        memory.addMessage("ttl-session", new ChatMessageDto(ChatMessageDto.Role.USER, "hello"));
        assertEquals(1, memory.getHistory("ttl-session").size());

        ticker.advance(Duration.ofSeconds(2));

        assertTrue(memory.getHistory("ttl-session").isEmpty());
        assertEquals(0, memory.getActiveSessionCount());
    }

    @Test
    void shouldHandleConcurrentWritesWithinLimit() throws InterruptedException {
        LlmConversationMemory memory = new LlmConversationMemory();
        MutableTicker ticker = new MutableTicker();
        memory.configureForTests(Duration.ofMinutes(60), 1_000, 50, ticker);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        IntStream.range(0, 300).forEach(i ->
                executor.submit(() -> memory.addMessage("session-concurrent",
                        new ChatMessageDto(ChatMessageDto.Role.USER, "m" + i))));

        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        List<ChatMessageDto> history = memory.getHistory("session-concurrent");
        assertTrue(finished);
        assertTrue(history.size() <= 50);
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
