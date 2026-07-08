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

    @Test
    void matchesOpenTelegramWithFillerWordsFromRealStt() {
        // Real STT output has filler words ("could you open A telegram") that defeat EXACT/CONTAINS;
        // the regex matcher catches the open+telegram keyword combo and routes to PC-control, not the LLM.
        Optional<VoiceCommandCatalog.Match> match = service.match("could you open a telegram", "en-US");

        assertTrue(match.isPresent());
        assertEquals("OPEN_APP", match.get().actionName());
        assertEquals("telegram", match.get().parameters().get("app"));
    }

    @Test
    void matchesOpenTelegramRussian() {
        Optional<VoiceCommandCatalog.Match> match = service.match("открой мне телеграм пожалуйста", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("OPEN_APP", match.get().actionName());
        assertEquals("telegram", match.get().parameters().get("app"));
    }

    @Test
    void matchesTurnOnMusicWithFillerWords() {
        Optional<VoiceCommandCatalog.Match> match = service.match("please turn on the music now", "en-US");

        assertTrue(match.isPresent());
        assertEquals("OPEN_URL", match.get().actionName());
    }

    // --- P0.1 Planner routing: these MUST resolve to PLANNER, not fall through to LLM chat ---

    @Test
    void routesKakiePlanyNaSegodnyaToPlanner() {
        Optional<VoiceCommandCatalog.Match> match = service.match("какие планы на сегодня", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("PLANNER_TODAY", match.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PLANNER, match.get().action().target());
    }

    @Test
    void routesChtoUMenyaSegodnyaToPlanner() {
        Optional<VoiceCommandCatalog.Match> match = service.match("что у меня сегодня", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("PLANNER_TODAY", match.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PLANNER, match.get().action().target());
    }

    @Test
    void routesZadachiNaSegodnyaToPlanner() {
        Optional<VoiceCommandCatalog.Match> match = service.match("джарвис какие задачи на сегодня", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("PLANNER_TODAY", match.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PLANNER, match.get().action().target());
    }

    @Test
    void routesFokusNaSegodnyaToPlanner() {
        Optional<VoiceCommandCatalog.Match> match = service.match("какой фокус на сегодня", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("PLANNER_TODAY", match.get().actionName());
    }

    // --- P0.2 Media next: phrase-coverage gap that used to fall through to LLM ---

    @Test
    void routesSleduyushchiyTrekToNext() {
        Optional<VoiceCommandCatalog.Match> match = service.match("следующий трек", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("NEXT", match.get().actionName());
    }

    @Test
    void routesSleduyushchayaPesnyaToNext() {
        Optional<VoiceCommandCatalog.Match> match = service.match("следующая песня", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("NEXT", match.get().actionName());
    }

    @Test
    void routesPereklyuchiTrekToNext() {
        Optional<VoiceCommandCatalog.Match> match = service.match("переключи трек", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("NEXT", match.get().actionName());
    }

    // --- P0.3 Volume regression: "тише" must keep routing to volume_down ---

    @Test
    void routesSdelatTisheToVolumeDown() {
        Optional<VoiceCommandCatalog.Match> match = service.match("сделай тише", "ru-RU");

        assertTrue(match.isPresent());
        assertTrue(
                isVolumeDown(match.get().actionName()),
                "expected a volume-down action but got " + match.get().actionName());
    }

    @Test
    void routesTisheToVolumeDown() {
        Optional<VoiceCommandCatalog.Match> match = service.match("тише", "ru-RU");

        assertTrue(match.isPresent());
        assertTrue(
                isVolumeDown(match.get().actionName()),
                "expected a volume-down action but got " + match.get().actionName());
    }

    // --- P0.4 Fallback: an unknown phrase yields no rule (handler then routes to LLM) ---

    @Test
    void unknownPhraseProducesNoRuleMatch() {
        Optional<VoiceCommandCatalog.Match> match =
                service.match("расскажи что-нибудь про квантовую запутанность", "ru-RU");

        assertTrue(match.isEmpty());
    }

    // --- Voice ↔ PC Control UI parity: voice builds the SAME action the working UI button uses ---
    // (desktop PcControlTab: Vol +10 → SystemControlService.changeVolume(10,"+") ≡ VOLUME_UP;
    //  Telegram button → openApp("telegram") ≡ OPEN_APP app=telegram; both converge on
    //  SystemControlService via PcControlWebSocketClient's VOLUME_UP / OPEN_APP mapping.)

    @Test
    void voiceGromcheBuildsSameVolumeUpActionAsPcControlButton() {
        Optional<VoiceCommandCatalog.Match> match = service.match("громче", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("VOLUME_UP", match.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PC_CONTROL, match.get().action().target());
    }

    @Test
    void voiceSdelayGromcheBuildsSameVolumeUpAction() {
        Optional<VoiceCommandCatalog.Match> match = service.match("сделай громче", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("VOLUME_UP", match.get().actionName());
    }

    @Test
    void voiceOtkroyTelegramBuildsSameOpenAppActionAsPcControlButton() {
        Optional<VoiceCommandCatalog.Match> match = service.match("открой телеграм", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("OPEN_APP", match.get().actionName());
        assertEquals("telegram", match.get().parameters().get("app"));
        assertEquals(VoiceCommandCatalog.ActionTarget.PC_CONTROL, match.get().action().target());
    }

    @Test
    void voiceVklyuchiMuzykuBuildsMediaOpenAction() {
        Optional<VoiceCommandCatalog.Match> match = service.match("включи музыку", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("OPEN_URL", match.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PC_CONTROL, match.get().action().target());
    }

    private static boolean isVolumeDown(String action) {
        if (action == null) {
            return false;
        }
        String normalized = action.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("VOLUME_DOWN") || normalized.equals("VOLUME_DOWN") || normalized.contains("QUIETER");
    }
}
