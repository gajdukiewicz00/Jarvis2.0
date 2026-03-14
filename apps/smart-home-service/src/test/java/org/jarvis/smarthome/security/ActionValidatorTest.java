package org.jarvis.smarthome.security;

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

class ActionValidatorTest {

    private ActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActionValidator();
        ReflectionTestUtils.setField(validator, "allowedActions", List.of("turn_on", "turn_off", "toggle", "dim"));
    }

    @Test
    void validateActionAllowsConfiguredActions() {
        assertDoesNotThrow(() -> validator.validateAction("turn_on"));
        assertDoesNotThrow(() -> validator.validateAction("TOGGLE"));
    }

    @Test
    void validateActionRejectsBlankInput() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validateAction(" "));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Action cannot be empty", exception.getReason());
    }

    @Test
    void validateActionRejectsUnsafeActions() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validateAction("unlock_front_door"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertTrue(exception.getReason().contains("unlock_front_door"));
    }
}
