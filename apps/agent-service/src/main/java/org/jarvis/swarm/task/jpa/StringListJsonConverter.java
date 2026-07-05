package org.jarvis.swarm.task.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps a {@code List<String>} (artifacts, risks) to a single JSON-array column. JPA
 * attribute converters are instantiated by Hibernate via reflection (not Spring), so this
 * uses its own stateless, thread-safe {@link ObjectMapper} rather than an injected bean.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize string list for persistence", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, LIST_OF_STRING);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot deserialize persisted string list", e);
        }
    }
}
