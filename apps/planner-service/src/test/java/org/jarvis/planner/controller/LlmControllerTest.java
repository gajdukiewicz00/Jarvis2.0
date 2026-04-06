package org.jarvis.planner.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmControllerTest {

    private final LlmController controller = new LlmController();

    @Test
    void generateDocumentReturnsNotImplementedInsteadOfPlaceholderContent() {
        var response = controller.generateDocument(
                new TestingAuthenticationToken("user-1", "n/a"),
                "email",
                "draft context");

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertEquals(Map.of(
                "status", "NOT_IMPLEMENTED",
                "documentType", "email",
                "message", "planner-service does not expose placeholder LLM document generation anymore; use llm-service directly"
        ), response.getBody());
    }

    @Test
    void parseTaskReturnsNotImplementedInsteadOfFakeParsing() {
        var response = controller.parseTask(
                new TestingAuthenticationToken("user-1", "n/a"),
                "Напомни позвонить маме завтра");

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertEquals(Map.of(
                "status", "NOT_IMPLEMENTED",
                "input", "Напомни позвонить маме завтра",
                "message", "planner-service does not expose placeholder LLM task parsing; use llm-service directly"
        ), response.getBody());
    }
}
