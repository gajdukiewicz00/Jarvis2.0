package org.jarvis.pccontrol.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCommandLocatorTest {

    private final PathCommandLocator locator = new PathCommandLocator();

    @Test
    void returnsFalseForNullCommand() {
        assertFalse(locator.isAvailable(null));
    }

    @Test
    void returnsFalseForBlankCommand() {
        assertFalse(locator.isAvailable("   "));
    }

    @Test
    void returnsTrueForExecutableAbsolutePath() {
        assertTrue(locator.isAvailable("/bin/sh"));
    }

    @Test
    void returnsFalseForNonExecutableAbsolutePath() {
        assertFalse(locator.isAvailable("/no/such/binary/here"));
    }

    @Test
    void returnsTrueForCommandFoundOnPath() {
        assertTrue(locator.isAvailable("ls"));
    }

    @Test
    void returnsFalseForCommandNotFoundOnPath() {
        assertFalse(locator.isAvailable("definitely-not-a-real-command-xyz"));
    }
}
