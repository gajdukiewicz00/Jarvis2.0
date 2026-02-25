package org.jarvis.llm.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void shouldFailValidationForBlankSessionAndEmptyMessages() {
        ChatRequestDto request = new ChatRequestDto("  ", List.of(), 100, 0.7);
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void shouldPassValidationForValidRequest() {
        ChatMessageDto message = new ChatMessageDto(ChatMessageDto.Role.USER, "Привет");
        ChatRequestDto request = new ChatRequestDto("session-1", List.of(message), 512, 0.7);
        assertTrue(validator.validate(request).isEmpty());
    }
}
