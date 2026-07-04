package org.jarvis.orchestrator.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceIntentTranslatorTest {

    private VoiceIntentTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new VoiceIntentTranslator();
        ReflectionTestUtils.setField(translator, "defaultBrowserUrl", "https://duckduckgo.com");
        ReflectionTestUtils.setField(translator, "defaultIdeApp", "code");
        ReflectionTestUtils.setField(translator, "defaultTerminalApp", "gnome-terminal");
        ReflectionTestUtils.setField(translator, "defaultYoutubeUrl", "https://www.youtube.com");
    }

    @Test
    void translatesOpenBrowserToOpenUrlWithDefaultUrl() {
        var result = translator.translate("open_browser", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("OPEN_URL");
        assertThat(result.payload())
                .containsEntry("url", "https://duckduckgo.com")
                .containsEntry("nlp_intent", "open_browser");
    }

    @Test
    void translatesOpenYoutubeToOpenUrl() {
        var result = translator.translate("open_youtube", Map.of());
        assertThat(result.agentIntent()).isEqualTo("OPEN_URL");
        assertThat(result.payload()).containsEntry("url", "https://www.youtube.com");
    }

    @Test
    void translatesOpenIdeWithDefaultApp() {
        var result = translator.translate("open_ide", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("OPEN_APP");
        assertThat(result.payload()).containsEntry("app", "code");
    }

    @Test
    void preservesExistingPayloadOverDefaults() {
        Map<String, Object> in = new HashMap<>();
        in.put("url", "https://example.org");
        var result = translator.translate("open_browser", in);
        assertThat(result.agentIntent()).isEqualTo("OPEN_URL");
        assertThat(result.payload()).containsEntry("url", "https://example.org");
    }

    @Test
    void translatesCreateNoteWithDateTitleDefault() {
        var result = translator.translate("create_note", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("CREATE_LOCAL_NOTE");
        assertThat(result.payload())
                .containsKey("title")
                .containsEntry("body", "");
        assertThat(result.payload().get("title").toString()).startsWith("Заметка ");
    }

    @Test
    void unknownIntentPassesThroughButRecordsNlpIntent() {
        var result = translator.translate("home.light.on", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("home.light.on");
        assertThat(result.payload()).containsEntry("nlp_intent", "home.light.on");
    }

    @Test
    void blankIntentReturnedAsIsNoNlpIntentTag() {
        var result = translator.translate("  ", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("  ");
        assertThat(result.payload()).doesNotContainKey("nlp_intent");
    }

    @Test
    void caseInsensitiveOnInputIntent() {
        var result = translator.translate("Open_Browser", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("OPEN_URL");
    }

    @Test
    void nullIntentReturnedAsIsNoNlpIntentTag() {
        var result = translator.translate(null, new HashMap<>());
        assertThat(result.agentIntent()).isNull();
        assertThat(result.payload()).doesNotContainKey("nlp_intent");
    }

    @Test
    void translatesOpenUrlPassThroughWithoutDefaultingUrl() {
        Map<String, Object> in = new HashMap<>();
        in.put("url", "https://example.org");
        var result = translator.translate("open_url", in);
        assertThat(result.agentIntent()).isEqualTo("OPEN_URL");
        assertThat(result.payload()).containsEntry("url", "https://example.org");
    }

    @Test
    void translatesOpenTerminalWithDefaultApp() {
        var result = translator.translate("open_terminal", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("OPEN_APP");
        assertThat(result.payload()).containsEntry("app", "gnome-terminal");
    }

    @Test
    void translatesOpenAppPassThrough() {
        Map<String, Object> in = new HashMap<>();
        in.put("app", "spotify");
        var result = translator.translate("open_app", in);
        assertThat(result.agentIntent()).isEqualTo("OPEN_APP");
        assertThat(result.payload()).containsEntry("app", "spotify");
    }

    @Test
    void translatesCreateLocalNoteAliasSameAsCreateNote() {
        var result = translator.translate("create_local_note", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("CREATE_LOCAL_NOTE");
        assertThat(result.payload()).containsEntry("body", "");
    }

    @Test
    void translatesMinimizeWindowUsingWindowKeyForTitle() {
        Map<String, Object> in = new HashMap<>();
        in.put("window", "Terminal");
        var result = translator.translate("minimize_window", in);
        assertThat(result.agentIntent()).isEqualTo("FOCUS_WINDOW");
        assertThat(result.payload()).containsEntry("title", "Terminal");
    }

    @Test
    void translatesMinimizeWindowFallsBackToTitleKeyWhenWindowAbsent() {
        Map<String, Object> in = new HashMap<>();
        in.put("title", "Editor");
        var result = translator.translate("minimize_window", in);
        assertThat(result.payload()).containsEntry("title", "Editor");
    }

    @Test
    void translatesMinimizeWindowDefaultsToEmptyTitleWhenNeitherKeyPresent() {
        var result = translator.translate("minimize_window", new HashMap<>());
        assertThat(result.payload()).containsEntry("title", "");
    }

    @Test
    void translatesShowNotificationWithDefaultSummary() {
        var result = translator.translate("show_notification", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("SHOW_NOTIFICATION");
        assertThat(result.payload()).containsEntry("summary", "Jarvis");
    }

    @Test
    void translatesGetActiveWindow() {
        var result = translator.translate("get_active_window", new HashMap<>());
        assertThat(result.agentIntent()).isEqualTo("GET_ACTIVE_WINDOW");
    }

    @Test
    void nullSlotsProduceEmptyPayloadForKnownIntent() {
        var result = translator.translate("open_app", null);
        assertThat(result.agentIntent()).isEqualTo("OPEN_APP");
        assertThat(result.payload()).containsEntry("nlp_intent", "open_app");
    }
}
