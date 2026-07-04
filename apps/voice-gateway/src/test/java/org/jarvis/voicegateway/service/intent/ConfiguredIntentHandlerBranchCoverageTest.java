package org.jarvis.voicegateway.service.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supplements {@link ConfiguredIntentHandlerTest} with branches it does not
 * yet exercise: {@code canHandle}, the empty-catalog fallback (handler used
 * before {@code load()} runs), and the English-language matching path.
 */
class ConfiguredIntentHandlerBranchCoverageTest {

    @Test
    void canHandleReturnsFalseForNullRequest() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();
        assertFalse(handler.canHandle(null));
    }

    @Test
    void canHandleReturnsFalseForNullText() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();
        assertFalse(handler.canHandle(IntentRequest.builder().text(null).build()));
    }

    @Test
    void canHandleReturnsFalseForBlankText() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();
        assertFalse(handler.canHandle(IntentRequest.builder().text("   ").build()));
    }

    @Test
    void canHandleReturnsTrueForNonBlankText() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();
        assertTrue(handler.canHandle(IntentRequest.builder().text("привет").build()));
    }

    @Test
    void handleReturnsUnknownWhenCatalogHasNotBeenLoaded() {
        // load() is only invoked via @PostConstruct in a Spring context; a handler
        // constructed directly (as in this test) starts with an empty command list,
        // exercising the commands.isEmpty() early-return branch.
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("открой ютуб")
                .correlationId("corr-empty")
                .build());

        assertFalse(result.isHandled());
        assertEquals("UNKNOWN", result.getAction());
        assertEquals("corr-empty", result.getCorrelationId());
        assertTrue(result.getParameters().isEmpty());
    }

    @Test
    void handleReturnsUnknownWithNullCorrelationIdWhenCatalogEmpty() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("открой ютуб")
                .build());

        assertFalse(result.isHandled());
        assertNull(result.getCorrelationId());
    }

    /**
     * Loaded handler shared by the language/matching tests below.
     */
    private ConfiguredIntentHandler loadedHandler() {
        ConfiguredIntentHandler handler = new ConfiguredIntentHandler();
        handler.load();
        return handler;
    }

    @Test
    void matchesEnglishPhraseWhenLanguageIsExplicitlyEnglish() {
        ConfiguredIntentHandler handler = loadedHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("play music")
                .language("en")
                .correlationId("corr-en")
                .build());

        assertTrue(result.isHandled());
        assertEquals("PLAY_MUSIC", result.getAction());
    }

    @Test
    void autoDetectsEnglishWhenLanguageNotProvided() {
        ConfiguredIntentHandler handler = loadedHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("play music")
                .correlationId("corr-auto-en")
                .build());

        assertTrue(result.isHandled());
        assertEquals("PLAY_MUSIC", result.getAction());
    }

    @Test
    void normalizesPunctuationAndYoBeforeMatching() {
        ConfiguredIntentHandler handler = loadedHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("Джарвис, ОТКРОЙ ЮТУБ!!!")
                .language("ru")
                .correlationId("corr-norm")
                .build());

        assertTrue(result.isHandled());
        assertEquals("OPEN_URL", result.getAction());
    }

    @Test
    void returnsUnknownForUnrecognizedTextWhenCatalogLoaded() {
        ConfiguredIntentHandler handler = loadedHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("совершенно неизвестная фраза без совпадений xyz")
                .correlationId("corr-unknown")
                .build());

        assertFalse(result.isHandled());
        assertEquals("UNKNOWN", result.getAction());
    }
}
