package org.jarvis.voicegateway.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceHelpControllerTest {

    private final VoiceHelpController controller = new VoiceHelpController();

    @Test
    void helpReturnsAssistantNameAndHint() {
        VoiceHelpController.HelpResponse response = controller.help();

        assertEquals("J.A.R.V.I.S.", response.assistant());
        assertEquals("Ты можешь сказать, сэр:", response.hint());
    }

    @Test
    void helpReturnsNonEmptyCategoriesWithCommands() {
        VoiceHelpController.HelpResponse response = controller.help();

        assertFalse(response.categories().isEmpty());
        response.categories().forEach(category -> {
            assertFalse(category.title().isBlank());
            assertFalse(category.commands().isEmpty());
        });
    }

    @Test
    void helpMarksScreenshotAndLockScreenAsMediumRiskRequiringConfirmation() {
        VoiceHelpController.HelpResponse response = controller.help();

        boolean foundMediumRiskConfirmed = response.categories().stream()
                .flatMap(c -> c.commands().stream())
                .anyMatch(cmd -> "screenshot".equals(cmd.intent())
                        && "MEDIUM".equals(cmd.risk())
                        && cmd.needsConfirmation());

        assertTrue(foundMediumRiskConfirmed);
    }

    @Test
    void helpReturnsTacticalHints() {
        VoiceHelpController.HelpResponse response = controller.help();

        assertFalse(response.tactical().isEmpty());
    }
}
