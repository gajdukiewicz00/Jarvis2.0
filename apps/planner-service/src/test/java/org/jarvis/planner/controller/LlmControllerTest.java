package org.jarvis.planner.controller;

import org.jarvis.planner.service.LlmEnhancementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmControllerTest {

    private final LlmEnhancementService enhancementService = mock(LlmEnhancementService.class);
    private final LlmController controller = new LlmController(enhancementService);

    @Test
    void generateDocumentReturnsOkWithLlmContent() {
        when(enhancementService.generateDocument("user-1", "email", "draft context"))
                .thenReturn("Dear sir, ...");

        var response = controller.generateDocument(
                new TestingAuthenticationToken("user-1", "n/a"), "email", "draft context");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody().get("status"));
        assertEquals("Dear sir, ...", response.getBody().get("content"));
    }

    @Test
    void parseTaskReturnsOkWithParsedJson() {
        when(enhancementService.parseNaturalLanguageTask("user-1", "Напомни позвонить маме завтра"))
                .thenReturn("{\"title\":\"позвонить маме\",\"priority\":\"MEDIUM\",\"dueHint\":\"завтра\"}");

        var response = controller.parseTask(
                new TestingAuthenticationToken("user-1", "n/a"), "Напомни позвонить маме завтра");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody().get("status"));
    }

    @Test
    void generateDocumentReturnsServiceUnavailableWhenLlmFails() {
        when(enhancementService.generateDocument("user-1", "email", "draft context"))
                .thenThrow(new RuntimeException("llm down"));

        var response = controller.generateDocument(
                new TestingAuthenticationToken("user-1", "n/a"), "email", "draft context");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("LLM_UNAVAILABLE", response.getBody().get("status"));
    }
}
