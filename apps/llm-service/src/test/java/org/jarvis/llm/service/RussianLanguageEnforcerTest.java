package org.jarvis.llm.service;

import org.jarvis.llm.config.LocaleConfig;
import org.jarvis.llm.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianLanguageEnforcerTest {

    private LocaleConfig localeConfig;
    private RussianLanguageEnforcer enforcer;

    @BeforeEach
    void setUp() {
        localeConfig = new LocaleConfig();
        enforcer = new RussianLanguageEnforcer(localeConfig);
    }

    @Test
    void enforceRussianInMessagesPrependsInstructionWhenEnabledAndMissing() {
        localeConfig.setForceRussian(true);
        List<ChatMessageDto> messages = new ArrayList<>(List.of(
                new ChatMessageDto(ChatMessageDto.Role.USER, "Hello there")));

        enforcer.enforceRussianInMessages(messages);

        assertEquals(2, messages.size());
        assertEquals(ChatMessageDto.Role.SYSTEM, messages.get(0).getRole());
        assertTrue(messages.get(0).getContent().contains("ТОЛЬКО на русском языке"));
    }

    @Test
    void enforceRussianInMessagesDoesNotDuplicateExistingInstruction() {
        localeConfig.setForceRussian(true);
        List<ChatMessageDto> messages = new ArrayList<>(List.of(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, "Отвечай только на русском языке."),
                new ChatMessageDto(ChatMessageDto.Role.USER, "Hello there")));

        enforcer.enforceRussianInMessages(messages);

        assertEquals(2, messages.size());
    }

    @Test
    void enforceRussianInMessagesSkipsChangesWhenFeatureDisabled() {
        localeConfig.setForceRussian(false);
        List<ChatMessageDto> messages = new ArrayList<>(List.of(
                new ChatMessageDto(ChatMessageDto.Role.USER, "Hello there")));

        enforcer.enforceRussianInMessages(messages);

        assertEquals(1, messages.size());
        assertEquals(ChatMessageDto.Role.USER, messages.get(0).getRole());
    }

    @Test
    void isRussianDistinguishesMostlyCyrillicFromLatinText() {
        assertTrue(enforcer.isRussian("Привет, как дела?"));
        assertTrue(enforcer.isRussian("12345 !!!"));
        assertFalse(enforcer.isRussian("Hello, how are you?"));
    }
}
