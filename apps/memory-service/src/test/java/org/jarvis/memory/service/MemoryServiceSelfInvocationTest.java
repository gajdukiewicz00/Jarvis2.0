package org.jarvis.memory.service;

import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.jarvis.memory.repository.SessionSummaryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression test: ingestAsync() must call ingest() through the Spring proxy
 * (via the {@code self} field), not via {@code this}. Without the proxy,
 * {@literal @}Transactional on ingest() is silently skipped (self-invocation bug).
 */
class MemoryServiceSelfInvocationTest {

    @Test
    void selfField_hasProxyInjectionAnnotations() {
        Field selfField = ReflectionUtils.findField(MemoryService.class, "self");
        assertNotNull(selfField, "MemoryService must have a 'self' field for proxy dispatch");
        assertTrue(selfField.isAnnotationPresent(Lazy.class),
                "self must be @Lazy to break the circular reference");
        assertTrue(selfField.isAnnotationPresent(Autowired.class),
                "self must be @Autowired for Spring injection");
        assertEquals(MemoryService.class, selfField.getType());
    }

    @Test
    void ingestAsync_delegatesThroughSelf_notThis() {
        MemoryService realService = new MemoryService(
                mock(ConversationMessageRepository.class),
                mock(MemoryChunkRepository.class),
                mock(SessionSummaryRepository.class),
                mock(EmbeddingClient.class),
                mock(ChunkingService.class)
        );

        MemoryService selfMock = mock(MemoryService.class);
        ReflectionTestUtils.setField(realService, "self", selfMock);

        IngestRequest request = IngestRequest.builder()
                .userId("u1")
                .sessionId("s1")
                .messages(List.of(
                        IngestRequest.MessageDto.builder()
                                .role("user")
                                .content("test")
                                .build()))
                .createChunks(false)
                .build();

        realService.ingestAsync(request, "corr-test");

        verify(selfMock).ingest(request, "corr-test");
        verifyNoMoreInteractions(selfMock);
    }
}
