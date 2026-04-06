package org.jarvis.voicegateway.service.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredIntentHandlerTest {

    private ConfiguredIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConfiguredIntentHandler();
        handler.load();
    }

    @Test
    void loadsMigratedLegacyCommandsFromYaml() {
        assertEquals(61, handler.getLoadedCommandsCount());
    }

    @Test
    void matchesLegacyWebsiteCommandAndCarriesUrlParameters() {
        IntentResult result = handle("джарвис открой ютуб");

        assertTrue(result.isHandled());
        assertEquals("OPEN_URL", result.getAction());
        assertEquals("https://www.youtube.com/", result.getParameters().get("url"));
    }

    @Test
    void keepsShortAliasMatchingOnWordBoundaries() {
        IntentResult result = handle("включи музыку");

        assertTrue(result.isHandled());
        assertEquals("PLAY_MUSIC", result.getAction());
    }

    @Test
    void stillMatchesStandaloneShortAlias() {
        IntentResult result = handle("вк");

        assertTrue(result.isHandled());
        assertEquals("OPEN_URL", result.getAction());
        assertEquals("https://vk.com/feed", result.getParameters().get("url"));
    }

    @Test
    void matchesLegacyStandbyPhraseFromConversationGroup() {
        IntentResult result = handle("я отдыхать");

        assertTrue(result.isHandled());
        assertEquals("STANDBY_MODE", result.getAction());
    }

    private IntentResult handle(String text) {
        return handler.handle(IntentRequest.builder()
                .text(text)
                .language("ru")
                .correlationId("test-correlation-id")
                .build());
    }
}
