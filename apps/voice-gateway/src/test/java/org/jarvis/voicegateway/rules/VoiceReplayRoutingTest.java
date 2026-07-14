package org.jarvis.voicegateway.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.jarvis.voicegateway.registry.VoiceServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replay tests for the exact real user phrases from the task: each must route deterministically
 * to the right service/intent, never to a generic LLM refusal. Actions are the concrete rule
 * action names (the conceptual intent from the task is noted in comments where they differ).
 */
class VoiceReplayRoutingTest {

    private RuleBasedVoiceCommandService service;
    private final VoiceServiceRegistry registry = new VoiceServiceRegistry();

    @BeforeEach
    void setUp() {
        VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader();
        loader.load();
        service = new RuleBasedVoiceCommandService(loader);
        service.init();
    }

    private String actionOf(String phrase) {
        Optional<VoiceCommandCatalog.Match> m = service.match(phrase, "ru-RU");
        assertTrue(m.isPresent(), "expected a rule match for: '" + phrase + "'");
        return m.get().actionName();
    }

    // ---- PC Control / Media / YouTube ----
    @Test void openYouTube_yutyub() { assertEquals("OPEN_URL", actionOf("открой ютюб")); }
    @Test void openVsCode_vesSkott() {
        var m = service.match("открой вес скотт", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_APP", m.get().actionName());
        assertEquals("vscode", m.get().parameters().get("app"));
    }
    @Test void minimizeAllWindows() { assertEquals("MINIMIZE_ALL_WINDOWS", actionOf("сверни все окна")); } // SHOW_DESKTOP concept
    @Test void youtubeSearch_naEtape() {
        var m = service.match("найди на этапе обзор фольксвагена", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_URL", m.get().actionName()); // YOUTUBE_SEARCH concept
        assertTrue(String.valueOf(m.get().parameters().get("url")).contains("youtube.com/results"));
    }

    // ---- Finance / Planner ----
    @Test void finance_chtoUNasFinansami() {
        var m = service.match("что у нас финансами", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("FINANCE_SUMMARY", m.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.FINANCE, m.get().action().target());
    }
    @Test void planner_kakiePlanyNaSegodnya() {
        var m = service.match("какие планы на сегодня", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("PLANNER_TODAY", m.get().actionName()); // PLANNER_TODAY_SUMMARY concept
        assertEquals(VoiceCommandCatalog.ActionTarget.PLANNER, m.get().action().target());
    }

    // ---- Info/status services (registry-routed) ----
    @Test void diagnostics() { assertEquals("DIAGNOSTICS_RUN", actionOf("проверь систему")); }
    @Test void serviceStatus() { assertEquals("SERVICE_STATUS_SUMMARY", actionOf("статус сервисов")); }
    @Test void aiRuntime() { assertEquals("AI_RUNTIME_STATUS", actionOf("статус ии")); }
    // Short / STT-cut variants of the AI-runtime-status intent must route to AI_RUNTIME_STATUS.
    @Test void aiRuntime_statusIiExisting() { assertEquals("AI_RUNTIME_STATUS", actionOf("статус ии")); }
    @Test void aiRuntime_statusISttCut() { assertEquals("AI_RUNTIME_STATUS", actionOf("статус и")); }
    @Test void aiRuntime_statusAi() { assertEquals("AI_RUNTIME_STATUS", actionOf("статус ai")); }
    @Test void aiRuntime_statusModeli() { assertEquals("AI_RUNTIME_STATUS", actionOf("статус модели")); }
    @Test void aiRuntime_proverModel() { assertEquals("AI_RUNTIME_STATUS", actionOf("проверь модель")); }
    @Test void aiRuntime_rabotaetLiGpu() { assertEquals("AI_RUNTIME_STATUS", actionOf("работает ли gpu")); }
    @Test void memorySearch() { assertEquals("MEMORY_SEARCH", actionOf("покажи память про джарвис")); }
    @Test void securityAudit() { assertEquals("SECURITY_AUDIT_RECENT", actionOf("покажи audit")); }
    @Test void pairing() { assertEquals("PAIRING_STATUS", actionOf("покажи pairing")); }
    @Test void agent() { assertEquals("AGENT_STATUS", actionOf("покажи агентов")); }
    @Test void mediaJobs() { assertEquals("MEDIA_JOBS_STATUS", actionOf("покажи медиа задачи")); }
    @Test void vision() { assertTrue(actionOf("проверь камеру").startsWith("VISION_")); }
    @Test void privacy() { assertEquals("PRIVACY_ENABLE", actionOf("включи приватность")); }
    @Test void smartHome() { assertEquals("SMART_HOME_STATUS", actionOf("покажи умный дом")); }
    @Test void settings() { assertEquals("SETTINGS_OPEN", actionOf("покажи настройки")); }
    @Test void settingsOpenIsRoutable() {
        // "открой настройки" opens the OS settings panel (existing PC-control rule) — routed, not LLM.
        assertTrue(service.match("открой настройки", "ru-RU").isPresent());
    }
    @Test void voiceCommandsCatalog() { assertEquals("VOICE_COMMANDS_CATALOG", actionOf("что ты умеешь")); }

    // ---- Regressions from latest test ----
    @Test void youtubeAnyVideo_lyubayaVideo() {
        // "открой любая видео на ютубе" → alias любая→любое → YouTube TRENDING, NOT PC-control generic.
        var m = service.match("открой любая видео на ютубе", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_URL", m.get().actionName());
        assertTrue(String.valueOf(m.get().parameters().get("url")).contains("youtube.com/feed/trending"));
    }
    @Test void youtubeAnyVideo_lyuboeVideo() {
        var m = service.match("открой любое видео на ютубе", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_URL", m.get().actionName());
        assertTrue(String.valueOf(m.get().parameters().get("url")).contains("youtube.com/feed/trending"));
    }
    @Test void youtubeFirstVideo_noQueryClarifies() {
        assertEquals("YOUTUBE_CLARIFY", actionOf("первое видео на ютубе"));
    }
    @Test void youtubeTopic_videoProKoshek() {
        var m = service.match("включи видео про кошек", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_URL", m.get().actionName());
        assertTrue(String.valueOf(m.get().parameters().get("url")).contains("youtube.com/results"));
        assertTrue(String.valueOf(m.get().parameters().get("url")).contains("кошек"));
    }

    // ---- Generic app-open (OPEN_APP with captured app name) ----
    @Test void openAppGeneric_kalkulyator() {
        var m = service.match("открой калькулятор", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_APP", m.get().actionName());
        assertEquals(VoiceCommandCatalog.ActionTarget.PC_CONTROL, m.get().action().target());
        assertEquals("калькулятор", m.get().parameters().get("app"));
    }
    @Test void openAppGeneric_veksel() {
        var m = service.match("открой вексель", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_APP", m.get().actionName());
        assertEquals("вексель", m.get().parameters().get("app"));
    }
    @Test void openAppGeneric_skott() {
        var m = service.match("открой скотт", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_APP", m.get().actionName());
        assertEquals("скотт", m.get().parameters().get("app"));
    }
    @Test void openAppGeneric_doesNotStealVsCodeAlias() {
        // "открой вес скотт" → normalizer → "открой vs code" → specific rule wins with app=vscode.
        var m = service.match("открой вес скотт", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_APP", m.get().actionName());
        assertEquals("vscode", m.get().parameters().get("app"));
    }

    // ---- Window restore vs minimize (generic must not steal either) ----
    @Test void restoreWindows_otkroyVseOkna() { assertEquals("RESTORE_WINDOWS", actionOf("открой все окна")); }
    @Test void restoreWindows_razverniVseOkna() { assertEquals("RESTORE_WINDOWS", actionOf("разверни все окна")); }
    @Test void minimizeAllWindows_sttVariant() {
        // "с кровь все окна" → normalizer → "сверни все окна" → MINIMIZE_ALL_WINDOWS.
        assertEquals("MINIMIZE_ALL_WINDOWS", actionOf("с кровь все окна"));
    }

    // ---- Vision screen analysis carries the confirm contract params ----
    @Test void visionScreen_carriesConfirmParams() {
        var m = service.match("что ты видишь на экране", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("VISION_SCREEN_ANALYZE_CONFIRM", m.get().actionName());
        assertEquals("VISION_SCREEN_ANALYZE", m.get().parameters().get("confirmAction"));
        assertEquals("vision", m.get().parameters().get("confirmTarget"));
    }

    // ---- Garbage STT stays unmatched (handler low-conf guard) ----
    @Test void garbageVargMogutHasNoRule() {
        assertTrue(service.match("варг могут", "ru-RU").isEmpty());
    }
    @Test void visionScreen_chtoVidishNaEkrane() {
        assertEquals("VISION_SCREEN_ANALYZE_CONFIRM", actionOf("что ты видишь на экране"));
    }
    @Test void visionScreen_proanaliziruy() {
        assertEquals("VISION_SCREEN_ANALYZE_CONFIRM", actionOf("проанализируй экран"));
    }
    @Test void volumeUp_specificResponse() {
        var m = service.match("сделай громче", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("VOLUME_UP", m.get().actionName());
        assertEquals("Делаю громче, сэр.", m.get().responseText("ru"));
    }
    @Test void openYouTubeStillOpens() {
        var m = service.match("открой ютуб", "ru-RU");
        assertTrue(m.isPresent());
        assertEquals("OPEN_URL", m.get().actionName());
    }

    // ---- Negative: no rule → handler guard applies ----
    @Test void garbageShortHasNoRule() {
        assertTrue(service.match("лет", "ru-RU").isEmpty()); // → ASK_REPEAT (handler low-conf guard)
    }
    @Test void unknownQuestionHasNoRuleAndNoDomain() {
        // → BRAIN_CHAT: no rule, and no known domain keyword.
        assertTrue(service.match("как думаешь стоит ли лететь на марс", "ru-RU").isEmpty());
        assertTrue(registry.matchDomain("как думаешь стоит ли лететь на марс").isEmpty());
    }

    // ---- Domain guard: known domains are recognized (never generic LLM) ----
    @Test void domainGuardRecognizesFinance() {
        assertTrue(registry.matchDomain("а что там вообще с финансами").isPresent());
        assertEquals("finance", registry.matchDomain("а что там вообще с финансами").get().serviceId());
    }
    @Test void domainGuardRecognizesSmartHome() {
        assertEquals("smart_home", registry.matchDomain("что там с умным домом").get().serviceId());
    }
    @Test void domainGuardRecognizesDiagnostics() {
        assertTrue(registry.matchDomain("почему диагностика показывает ошибку").isPresent());
    }

    // ---- Catalog is non-empty and mentions core services ----
    @Test void catalogMentionsCoreServices() {
        String cat = registry.catalogText(true);
        assertTrue(cat.contains("финанс") && cat.contains("планер") && cat.contains("компьютер"), cat);
    }

    @Test void everyServiceHasFallbackMessage() {
        for (VoiceServiceRegistry.ServiceEntry s : registry.services()) {
            assertFalse(s.fallbackRu() == null || s.fallbackRu().isBlank(),
                    "service " + s.serviceId() + " must have a fallback message");
        }
    }
}
