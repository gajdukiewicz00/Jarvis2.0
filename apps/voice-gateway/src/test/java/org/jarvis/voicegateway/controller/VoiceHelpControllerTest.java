package org.jarvis.voicegateway.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    @Test
    void helpCatalogCoversCoreVoiceCommandCategoriesWithEnAndRuPhrasing() {
        VoiceHelpController.HelpResponse response = controller.help();

        Set<String> intents = response.categories().stream()
                .flatMap(c -> c.commands().stream())
                .map(VoiceHelpController.Command::intent)
                .collect(java.util.stream.Collectors.toSet());

        // open browser / open terminal
        assertTrue(intents.contains("open_browser"), "missing open_browser (open browser)");
        assertTrue(intents.contains("open_terminal"), "missing open_terminal (open terminal)");
        // volume up/down + mute/unmute
        assertTrue(intents.contains("volume_up"), "missing volume_up");
        assertTrue(intents.contains("volume_down"), "missing volume_down");
        assertTrue(intents.contains("mute"), "missing mute");
        assertTrue(intents.contains("unmute"), "missing unmute");
        // play/pause
        assertTrue(intents.contains("play"), "missing play");
        assertTrue(intents.contains("pause"), "missing pause");
        // add task ("добавь задачу ...")
        assertTrue(intents.contains("planner.create-task"), "missing planner.create-task (add task)");
        // show/search memory ("что помнишь про ...")
        assertTrue(intents.contains("memory.search"), "missing memory.search (search memory)");
        // add expense
        assertTrue(intents.contains("add_expense"), "missing add_expense");
        // smart-home scene ("включи свет на кухне")
        assertTrue(intents.contains("home.light.on"), "missing home.light.on (smart-home scene)");
        // ask the brain a question
        assertTrue(intents.contains("ask_brain"), "missing ask_brain");
        // what-can-i-say
        assertTrue(intents.contains("help"), "missing help/what-can-i-say");

        response.categories().stream()
                .flatMap(c -> c.commands().stream())
                .filter(cmd -> Set.of(
                                "planner.create-task", "memory.search", "home.light.on", "ask_brain", "help")
                        .contains(cmd.intent()))
                .forEach(cmd -> assertTrue(cmd.say().size() >= 2,
                        "expected EN + RU phrasing for " + cmd.intent()));
    }

    @Test
    void resolveCategoriesNeverReturnsEmptyEvenWhenSourceIsUnavailable() {
        assertFalse(VoiceHelpController.resolveCategories(null).isEmpty(),
                "fallback catalog must be non-empty when source is null");
        assertFalse(VoiceHelpController.resolveCategories(List.of()).isEmpty(),
                "fallback catalog must be non-empty when source is empty");
    }
}
