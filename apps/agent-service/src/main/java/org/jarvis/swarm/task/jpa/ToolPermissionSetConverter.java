package org.jarvis.swarm.task.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jarvis.common.safety.ToolPermission;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps a {@code Set<ToolPermission>} to a single comma-separated column (e.g.
 * {@code "READ_FILES,WRITE_FILES"}). Permission names are plain enum identifiers with no
 * delimiter characters, so CSV round-trips exactly with no escaping needed.
 */
@Converter
public class ToolPermissionSetConverter implements AttributeConverter<Set<ToolPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<ToolPermission> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "";
        }
        return attribute.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<ToolPermission> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ToolPermission::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
