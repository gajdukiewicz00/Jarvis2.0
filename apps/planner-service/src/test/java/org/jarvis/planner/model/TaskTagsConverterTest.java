package org.jarvis.planner.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTagsConverterTest {

    private final TaskTagsConverter converter = new TaskTagsConverter();

    @Test
    void convertToDatabaseColumnReturnsNullOnlyForMissingCollections() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    void convertToDatabaseColumnReturnsEmptyStringWhenOnlyBlankTagsRemainAfterFiltering() {
        assertEquals("", converter.convertToDatabaseColumn(Arrays.asList(" ", "", "\t")));
    }

    @Test
    void convertToDatabaseColumnTrimsAndPreservesOrder() {
        assertEquals(
                "work,urgent,deep-focus",
                converter.convertToDatabaseColumn(Arrays.asList(" work ", "urgent", "", " deep-focus ")));
    }

    @Test
    void convertToEntityAttributeReturnsEmptyListForBlankStorageValue() {
        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
        assertTrue(converter.convertToEntityAttribute(" ").isEmpty());
    }

    @Test
    void convertToEntityAttributeSplitsAndTrimsTags() {
        assertEquals(
                List.of("work", "urgent", "deep-focus"),
                converter.convertToEntityAttribute(" work , urgent,, deep-focus "));
    }
}
