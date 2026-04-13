package org.jarvis.voicegateway;

import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog;
import org.jarvis.voicegateway.rules.VoiceCommandCatalogLoader;
import org.jarvis.voicegateway.service.intent.BasicIntentHandler;
import org.jarvis.voicegateway.service.intent.IntentRequest;
import org.jarvis.voicegateway.service.intent.IntentResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvertisedVoiceCommandCoverageTest {

    @Test
    void desktopUiExamplePhraseRemainsSupported() {
        BasicIntentHandler handler = new BasicIntentHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("прибавь громкость")
                .language("ru")
                .correlationId("desktop-ui-example")
                .build());

        assertTrue(result.isHandled());
        assertEquals("VOLUME_UP", result.getAction());
    }

    @Test
    void documentedGoodMorningPhraseUsesRuleCatalogPath() {
        VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader();
        loader.load();
        RuleBasedVoiceCommandService service = new RuleBasedVoiceCommandService(loader);
        service.init();

        Optional<VoiceCommandCatalog.Match> match = service.match("good morning", "en-US");

        assertTrue(match.isPresent());
        assertEquals("MORNING_GREETING", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.EXACT, match.get().matcherType());
    }

    @Test
    void documentedWhatsUpPhraseRemainsInBoundedFallbackSet() {
        BasicIntentHandler handler = new BasicIntentHandler();

        IntentResult result = handler.handle(IntentRequest.builder()
                .text("what's up")
                .language("en")
                .correlationId("fallback-safe-example")
                .build());

        assertTrue(result.isHandled());
        assertEquals("HOW_ARE_YOU", result.getAction());
    }
}
