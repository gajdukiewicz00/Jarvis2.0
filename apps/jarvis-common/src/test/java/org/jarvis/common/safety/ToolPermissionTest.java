package org.jarvis.common.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolPermissionTest {

    @Test
    void definesAllTwelveGranularCapabilities() {
        ToolPermission[] values = ToolPermission.values();

        assertEquals(12, values.length);
        assertEquals(ToolPermission.READ_FILES, ToolPermission.valueOf("READ_FILES"));
        assertEquals(ToolPermission.PLANNER_ACCESS, ToolPermission.valueOf("PLANNER_ACCESS"));
    }

    @Test
    void valueOfRejectsUnknownConstant() {
        assertThrows(IllegalArgumentException.class, () -> ToolPermission.valueOf("NOT_A_PERMISSION"));
    }
}
