package org.jarvis.swarm.task.jpa;

import org.jarvis.common.safety.ToolPermission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeConvertersTest {

    private final ToolPermissionSetConverter permissionConverter = new ToolPermissionSetConverter();
    private final StringListJsonConverter listConverter = new StringListJsonConverter();

    @Test
    void permissionSetRoundTripsThroughCsv() {
        Set<ToolPermission> perms = Set.of(ToolPermission.READ_FILES, ToolPermission.RUN_SHELL);

        String column = permissionConverter.convertToDatabaseColumn(perms);
        Set<ToolPermission> restored = permissionConverter.convertToEntityAttribute(column);

        assertThat(restored).containsExactlyInAnyOrder(ToolPermission.READ_FILES, ToolPermission.RUN_SHELL);
    }

    @Test
    void emptyOrNullPermissionSetRoundTripsToEmptySet() {
        assertThat(permissionConverter.convertToDatabaseColumn(null)).isEmpty();
        assertThat(permissionConverter.convertToDatabaseColumn(Set.of())).isEmpty();
        assertThat(permissionConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(permissionConverter.convertToEntityAttribute("")).isEmpty();
        assertThat(permissionConverter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    void stringListRoundTripsThroughJson() {
        List<String> artifacts = List.of("/tmp/a.txt", "/tmp/b with spaces.txt", "risk: \"quoted\"");

        String column = listConverter.convertToDatabaseColumn(artifacts);
        List<String> restored = listConverter.convertToEntityAttribute(column);

        assertThat(restored).containsExactlyElementsOf(artifacts);
    }

    @Test
    void emptyOrNullStringListRoundTripsToEmptyList() {
        assertThat(listConverter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(listConverter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
        assertThat(listConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(listConverter.convertToEntityAttribute("")).isEmpty();
    }
}
