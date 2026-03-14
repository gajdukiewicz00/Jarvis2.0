package org.jarvis.pccontrol.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandValidatorTest {

    private CommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CommandValidator();
        ReflectionTestUtils.setField(validator, "allowedActions", List.of("mute", "volume_up", "open_browser"));
    }

    @Test
    void validateActionAllowsConfiguredActions() {
        assertDoesNotThrow(() -> validator.validateAction("mute"));
    }

    @Test
    void validateActionRejectsBlankInput() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validateAction(" "));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Action type cannot be empty", exception.getReason());
    }

    @Test
    void validateActionRejectsUnsafeActions() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validateAction("shutdown"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertTrue(exception.getReason().contains("shutdown"));
    }
}
