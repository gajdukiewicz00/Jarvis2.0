package org.jarvis.voicegateway.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedVoiceCommandServiceTest {

    private RuleBasedVoiceCommandService service;

    @BeforeEach
    void setUp() {
        VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader();
        loader.load();
        service = new RuleBasedVoiceCommandService(loader);
        service.init();
    }

    @Test
    void loadsSeededRuleCommandsFromYaml() {
        assertTrue(service.getLoadedCommandCount() >= 270);
    }

    @Test
    void matchesNormalizedExactPhrase() {
        Optional<VoiceCommandCatalog.Match> match = service.match("Доброе утро!!!", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("MORNING_GREETING", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.EXACT, match.get().matcherType());
    }

    @Test
    void matchesAliasPhrase() {
        Optional<VoiceCommandCatalog.Match> match = service.match("гугл", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("OPEN_URL", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.ALIAS, match.get().matcherType());
    }

    @Test
    void matchesContainsPhraseInsideLongerTranscript() {
        Optional<VoiceCommandCatalog.Match> match = service.match("джарвис пожалуйста открой браузер прямо сейчас", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("OPEN_APP", match.get().actionName());
        assertEquals("browser", match.get().parameters().get("app"));
        assertEquals(VoiceCommandCatalog.MatcherType.CONTAINS, match.get().matcherType());
    }

    @Test
    void supportsRegexMatchersForFutureSlotStyleExtensions() {
        Optional<VoiceCommandCatalog.Match> match = service.match("режим 42", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("TEST_REGEX", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.REGEX, match.get().matcherType());
        assertEquals("42", match.get().parameters().get("slot"));
    }

    @Test
    void matchesLegacyDynamicVolumeCommandAndResolvesCapturedLevel() {
        Optional<VoiceCommandCatalog.Match> match = service.match("громкость на 35 процентов", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("SET_VOLUME", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.REGEX, match.get().matcherType());
        assertEquals("35", match.get().parameters().get("level"));
    }
}
