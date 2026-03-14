package org.jarvis.lifetracker.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.common.exception.IdempotencyConflictException;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.tooling.dto.BudgetStatusToolRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
        BudgetStatusToolRequest request = new BudgetStatusToolRequest();
        request.setMonth("2026-03");

        String firstHash = toolRequestService.hashRequest(request);
        String secondHash = toolRequestService.hashRequest(request);

        assertEquals(firstHash, secondHash);
        assertEquals(64, firstHash.length());
    }

    @Test
    void loadCachedResponseReturnsDeserializedPayloadForMatchingRequest() throws Exception {
        ExpenseDTO cachedExpense = new ExpenseDTO();
        cachedExpense.setId(7L);
        cachedExpense.setAmount(new BigDecimal("25.00"));
        cachedExpense.setCategory("food");

        ToolRequest storedRequest = new ToolRequest();
        storedRequest.setIdempotencyKey("key-1");
        storedRequest.setToolName("budget_status");
        storedRequest.setUserId("user-123");
        storedRequest.setRequestHash("hash-1");
        storedRequest.setResponseBody(objectMapper.writeValueAsString(cachedExpense));

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(storedRequest));

        Optional<ExpenseDTO> result = toolRequestService.loadCachedResponse(
                "key-1",
                "budget_status",
                "user-123",
                "hash-1",
                ExpenseDTO.class);

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().getId());
        assertEquals(new BigDecimal("25.00"), result.get().getAmount());
        assertEquals("food", result.get().getCategory());
    }

    @Test
    void loadCachedResponseRejectsKeyReuseForDifferentPayload() {
        ToolRequest storedRequest = new ToolRequest();
        storedRequest.setIdempotencyKey("key-1");
        storedRequest.setToolName("budget_status");
        storedRequest.setUserId("user-123");
        storedRequest.setRequestHash("hash-1");

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(storedRequest));

        IdempotencyConflictException exception = assertThrows(
                IdempotencyConflictException.class,
                () -> toolRequestService.loadCachedResponse(
                        "key-1",
                        "budget_status",
                        "user-123",
                        "hash-2",
                        ExpenseDTO.class));

        assertTrue(exception.getMessage().contains("different request payload"));
    }

    @Test
    void storeResponsePersistsSerializedResponseBody() {
        ExpenseDTO response = new ExpenseDTO();
        response.setId(42L);
        response.setCategory("health");

        when(toolRequestRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        toolRequestService.storeResponse("key-1", "budget_status", "user-123", "hash-1", response);

        ArgumentCaptor<ToolRequest> captor = ArgumentCaptor.forClass(ToolRequest.class);
        verify(toolRequestRepository).save(captor.capture());

        ToolRequest saved = captor.getValue();
        assertEquals("key-1", saved.getIdempotencyKey());
        assertEquals("budget_status", saved.getToolName());
        assertEquals("user-123", saved.getUserId());
        assertEquals("hash-1", saved.getRequestHash());
        assertNotNull(saved.getResponseBody());
        assertTrue(saved.getResponseBody().contains("\"id\":42"));
    }
}
