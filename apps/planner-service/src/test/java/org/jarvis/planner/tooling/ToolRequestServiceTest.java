package org.jarvis.planner.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.common.exception.IdempotencyConflictException;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.tooling.dto.CreateTodoRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRequestServiceTest {

    @Mock
    private ToolRequestRepository toolRequestRepository;

    private ToolRequestService toolRequestService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        toolRequestService = new ToolRequestService(toolRequestRepository, objectMapper);
    }

    @Test
    void hashRequestIsDeterministicForTheSamePayload() {
        CreateTodoRequest request = new CreateTodoRequest();
        request.setTitle("Pay bills");
        request.setTags(List.of("finance", "urgent"));

        String firstHash = toolRequestService.hashRequest(request);
        String secondHash = toolRequestService.hashRequest(request);

        assertEquals(firstHash, secondHash);
        assertEquals(64, firstHash.length());
    }

    @Test
    void loadCachedResponseReturnsDeserializedPayloadForMatchingRequest() throws Exception {
        TaskDto cachedTask = new TaskDto();
        cachedTask.setId(7L);
        cachedTask.setTitle("Pay bills");

        ToolRequest storedRequest = new ToolRequest();
        storedRequest.setIdempotencyKey("key-1");
        storedRequest.setToolName("create_todo");
        storedRequest.setUserId("user-123");
        storedRequest.setRequestHash("hash-1");
        storedRequest.setResponseBody(objectMapper.writeValueAsString(cachedTask));

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(storedRequest));

        Optional<TaskDto> result = toolRequestService.loadCachedResponse(
                "key-1",
                "create_todo",
                "user-123",
                "hash-1",
                TaskDto.class);

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().getId());
        assertEquals("Pay bills", result.get().getTitle());
    }

    @Test
    void loadCachedResponseRejectsKeyReuseForDifferentPayload() {
        ToolRequest storedRequest = new ToolRequest();
        storedRequest.setIdempotencyKey("key-1");
        storedRequest.setToolName("create_todo");
        storedRequest.setUserId("user-123");
        storedRequest.setRequestHash("hash-1");

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(storedRequest));

        IdempotencyConflictException exception = assertThrows(
                IdempotencyConflictException.class,
                () -> toolRequestService.loadCachedResponse(
                        "key-1",
                        "create_todo",
                        "user-123",
                        "hash-2",
                        TaskDto.class));

        assertTrue(exception.getMessage().contains("different request payload"));
    }

    @Test
    void storeResponsePersistsSerializedResponseBody() {
        TaskDto response = new TaskDto();
        response.setId(42L);
        response.setTitle("Pay bills");

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        toolRequestService.storeResponse("key-1", "create_todo", "user-123", "hash-1", response);

        ArgumentCaptor<ToolRequest> captor = ArgumentCaptor.forClass(ToolRequest.class);
        verify(toolRequestRepository).save(captor.capture());

        ToolRequest saved = captor.getValue();
        assertEquals("key-1", saved.getIdempotencyKey());
        assertEquals("create_todo", saved.getToolName());
        assertEquals("user-123", saved.getUserId());
        assertEquals("hash-1", saved.getRequestHash());
        assertNotNull(saved.getResponseBody());
        assertTrue(saved.getResponseBody().contains("\"id\":42"));
    }
}
