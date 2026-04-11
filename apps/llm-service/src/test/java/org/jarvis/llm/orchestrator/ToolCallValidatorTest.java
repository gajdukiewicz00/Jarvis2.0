package org.jarvis.llm.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.llm.orchestrator.dto.ModelToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallValidatorTest {

    private ToolCallValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolCallValidator(new ObjectMapper());
        validator.loadFromParsed(List.of(
                Map.of("name", "create_todo", "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "minLength", 1),
                                "priority", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH", "URGENT")),
                                "tags", Map.of("type", "array")
                        ),
                        "required", List.of("title")
                )),
                Map.of("name", "complete_todo", "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "integer", "minimum", 1)
                        ),
                        "required", List.of("id")
                ))
        ));
    }

    @Test
    void validToolCallPasses() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of("title", "Buy milk", "priority", "HIGH"));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertTrue(result.valid(), "Expected valid, got errors: " + result.errors());
    }

    @Test
    void unknownToolRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("delete_database");
        call.setArguments(Map.of());

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("unknown tool")));
    }

    @Test
    void missingRequiredFieldRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of("priority", "LOW"));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing required field: title")));
    }

    @Test
    void invalidEnumValueRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of("title", "Test", "priority", "CRITICAL"));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("invalid enum value")));
    }

    @Test
    void typeMismatchRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("complete_todo");
        call.setArguments(Map.of("id", "not-a-number"));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("expected integer")));
    }

    @Test
    void belowMinimumRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("complete_todo");
        call.setArguments(Map.of("id", 0));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("below minimum")));
    }

    @Test
    void missingNameRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName(null);
        call.setArguments(Map.of());

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing name")));
    }

    @Test
    void unknownFieldRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of("title", "Test", "malicious_field", "drop tables"));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("unknown field")));
    }

    @Test
    void emptyStringTitleRejected() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of("title", ""));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("too short")));
    }

    @Test
    void knownToolsMatchesRegistry() {
        assertEquals(2, validator.knownTools().size());
        assertTrue(validator.knownTools().contains("create_todo"));
        assertTrue(validator.knownTools().contains("complete_todo"));
    }

    // ── Hardening regression: malformed payloads ──────────────────────

    @Test
    void nullArgumentsMapRejectedGracefully() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(null);

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing required field: title")));
    }

    @Test
    void multipleViolationsReportedTogether() {
        ModelToolCall call = new ModelToolCall();
        call.setName("create_todo");
        call.setArguments(Map.of(
                "priority", "CRITICAL",
                "injected_field", "payload"
        ));

        ToolCallValidator.ValidationResult result = validator.validate(call);
        assertFalse(result.valid());
        assertTrue(result.errors().size() >= 2,
                "Expected at least 2 errors (missing title + extra field or bad enum), got: " + result.errors());
    }
}
