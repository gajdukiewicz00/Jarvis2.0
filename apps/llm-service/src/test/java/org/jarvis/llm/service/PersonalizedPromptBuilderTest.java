package org.jarvis.llm.service;

import org.jarvis.llm.model.CommunicationStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalizedPromptBuilderTest {

    private PersonalizedPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PersonalizedPromptBuilder();
    }

    @Test
    void buildSystemPromptUsesFallbackValuesWhenOptionalInputsAreMissing() {
        String prompt = builder.buildSystemPrompt(
                null,
                null,
                null,
                List.of(),
                CommunicationStyle.FORMAL,
                false);

        assertTrue(prompt.contains("персональный ИИ-ассистент пользователя Denis"));
        assertTrue(prompt.contains("Europe/Warsaw"));
        assertTrue(prompt.contains("построить Jarvis систему, карьера в IT"));
        assertTrue(prompt.contains("никогда не саркастичный"));
    }

    @Test
    void buildSystemPromptIncludesProvidedProfileAndStyleInstructions() {
        String prompt = builder.buildSystemPrompt(
                "Anna",
                "Europe/Berlin",
                "engineer",
                List.of("finish Jarvis", "ship tests"),
                CommunicationStyle.CONCISE,
                true);

        assertTrue(prompt.contains("Anna"));
        assertTrue(prompt.contains("Europe/Berlin"));
        assertTrue(prompt.contains("engineer"));
        assertTrue(prompt.contains("finish Jarvis, ship tests"));
        assertTrue(prompt.contains("Отвечай максимально кратко"));
        assertFalse(prompt.contains("никогда не саркастичный"));
    }
}
