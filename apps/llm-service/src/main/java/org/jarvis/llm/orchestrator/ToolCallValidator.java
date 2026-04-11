package org.jarvis.llm.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.orchestrator.dto.ModelToolCall;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates LLM-originated tool calls against the registry.json schemas.
 * Rejects unknown tools, missing required fields, type mismatches, and invalid enum values.
 * Fail-closed: any validation failure results in rejection.
 */
@Slf4j
@Component
public class ToolCallValidator {

    private static final String REGISTRY_PATH = "tools/registry.json";

    private final ObjectMapper objectMapper;
    private Map<String, ToolSchema> toolSchemas = Map.of();

    public ToolCallValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSchemas() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(REGISTRY_PATH)) {
            if (is == null) {
                log.error("Tool registry not found at classpath:{}", REGISTRY_PATH);
                return;
            }
            List<Map<String, Object>> tools = objectMapper.readValue(is, new TypeReference<>() {});
            Map<String, ToolSchema> schemas = new HashMap<>();
            for (Map<String, Object> tool : tools) {
                String name = (String) tool.get("name");
                if (name == null) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> inputSchema = (Map<String, Object>) tool.get("input_schema");
                schemas.put(name, ToolSchema.fromMap(inputSchema));
            }
            this.toolSchemas = Map.copyOf(schemas);
            log.info("Loaded {} tool schemas from {}", schemas.size(), REGISTRY_PATH);
        } catch (Exception e) {
            log.error("Failed to load tool registry: {}", e.getMessage(), e);
        }
    }

    public Set<String> knownTools() {
        return toolSchemas.keySet();
    }

    public ValidationResult validate(ModelToolCall call) {
        List<String> errors = new ArrayList<>();

        if (call.getName() == null || call.getName().isBlank()) {
            errors.add("tool call missing name");
            return new ValidationResult(false, errors);
        }

        String toolName = call.getName();
        ToolSchema schema = toolSchemas.get(toolName);
        if (schema == null) {
            errors.add("unknown tool: " + toolName);
            return new ValidationResult(false, errors);
        }

        Map<String, Object> args = call.getArguments();
        if (args == null) {
            args = Map.of();
        }

        for (String required : schema.required) {
            if (!args.containsKey(required) || args.get(required) == null) {
                errors.add("missing required field: " + required);
            }
        }

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            PropertySchema prop = schema.properties.get(field);
            if (prop == null) {
                errors.add("unknown field: " + field);
                continue;
            }
            validateProperty(field, value, prop, errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private void validateProperty(String field, Object value, PropertySchema prop, List<String> errors) {
        if (value == null) return;

        switch (prop.type) {
            case "string" -> {
                if (!(value instanceof String)) {
                    errors.add(field + ": expected string, got " + value.getClass().getSimpleName());
                } else if (prop.enumValues != null && !prop.enumValues.contains(value)) {
                    errors.add(field + ": invalid enum value '" + value + "', allowed: " + prop.enumValues);
                } else if (prop.minLength > 0 && ((String) value).length() < prop.minLength) {
                    errors.add(field + ": string too short (min " + prop.minLength + ")");
                }
            }
            case "integer" -> {
                if (!(value instanceof Number)) {
                    errors.add(field + ": expected integer, got " + value.getClass().getSimpleName());
                } else {
                    long num = ((Number) value).longValue();
                    if (prop.minimum != null && num < prop.minimum) {
                        errors.add(field + ": value " + num + " below minimum " + prop.minimum);
                    }
                    if (prop.maximum != null && num > prop.maximum) {
                        errors.add(field + ": value " + num + " above maximum " + prop.maximum);
                    }
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add(field + ": expected boolean, got " + value.getClass().getSimpleName());
                }
            }
            case "array" -> {
                if (!(value instanceof List)) {
                    errors.add(field + ": expected array, got " + value.getClass().getSimpleName());
                }
            }
        }
    }

    /** Load schemas from a pre-parsed list (for testing). */
    void loadFromParsed(List<Map<String, Object>> tools) {
        Map<String, ToolSchema> schemas = new HashMap<>();
        for (Map<String, Object> tool : tools) {
            String name = (String) tool.get("name");
            if (name == null) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = (Map<String, Object>) tool.get("input_schema");
            schemas.put(name, ToolSchema.fromMap(inputSchema));
        }
        this.toolSchemas = Map.copyOf(schemas);
    }

    public record ValidationResult(boolean valid, List<String> errors) {}

    static class ToolSchema {
        Map<String, PropertySchema> properties = Map.of();
        List<String> required = List.of();

        @SuppressWarnings("unchecked")
        static ToolSchema fromMap(Map<String, Object> map) {
            ToolSchema schema = new ToolSchema();
            if (map == null) return schema;

            Object props = map.get("properties");
            if (props instanceof Map<?, ?> propsMap) {
                Map<String, PropertySchema> parsed = new HashMap<>();
                for (Map.Entry<?, ?> e : propsMap.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> propDef) {
                        parsed.put(String.valueOf(e.getKey()), PropertySchema.fromMap((Map<String, Object>) propDef));
                    }
                }
                schema.properties = Map.copyOf(parsed);
            }

            Object req = map.get("required");
            if (req instanceof List<?> reqList) {
                schema.required = reqList.stream().map(String::valueOf).toList();
            }
            return schema;
        }
    }

    static class PropertySchema {
        String type = "string";
        List<String> enumValues;
        int minLength;
        Long minimum;
        Long maximum;

        @SuppressWarnings("unchecked")
        static PropertySchema fromMap(Map<String, Object> map) {
            PropertySchema ps = new PropertySchema();
            if (map == null) return ps;
            Object type = map.get("type");
            if (type instanceof String t) ps.type = t;
            Object en = map.get("enum");
            if (en instanceof List<?> enumList) {
                ps.enumValues = enumList.stream().map(String::valueOf).toList();
            }
            Object ml = map.get("minLength");
            if (ml instanceof Number n) ps.minLength = n.intValue();
            Object min = map.get("minimum");
            if (min instanceof Number n) ps.minimum = n.longValue();
            Object max = map.get("maximum");
            if (max instanceof Number n) ps.maximum = n.longValue();
            return ps;
        }
    }
}
