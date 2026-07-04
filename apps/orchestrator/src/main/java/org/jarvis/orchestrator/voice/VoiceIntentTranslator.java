package org.jarvis.orchestrator.voice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P2 — translates user-facing NLP intents (lowercase, snake_case) into the
 * desktop-agent's safe intent allowlist (uppercase, snake_case).
 *
 * <p>nlp-service produces names like {@code open_browser}, {@code open_youtube},
 * {@code create_note}; the desktop executor only knows the small allowlist in
 * {@code NativeDesktopCommandExecutor}: {@code OPEN_URL}, {@code OPEN_APP},
 * {@code CREATE_LOCAL_NOTE}, etc. Without translation the executor would reject
 * the command as "not implemented in safe executor". This class is the single
 * place that bridges those name spaces and supplies missing payload defaults
 * (e.g. a default URL for {@code open_browser}).</p>
 *
 * <p>The original NLP intent is preserved in the payload as {@code nlp_intent}
 * for traceability in the audit log.</p>
 */
@Slf4j
@Component
public class VoiceIntentTranslator {

    @Value("${jarvis.voice.translator.default-browser-url:https://duckduckgo.com}")
    private String defaultBrowserUrl;

    @Value("${jarvis.voice.translator.default-ide-app:code}")
    private String defaultIdeApp;

    @Value("${jarvis.voice.translator.default-terminal-app:gnome-terminal}")
    private String defaultTerminalApp;

    @Value("${jarvis.voice.translator.default-youtube-url:https://www.youtube.com}")
    private String defaultYoutubeUrl;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Translate an NLP intent and its slot map into an executor-ready
     * (intent, payload) pair. Unknown intents are passed through untouched
     * so the catalog/executor can still reject them.
     */
    public Translated translate(String nlpIntent, Map<String, Object> slots) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (slots != null) {
            payload.putAll(slots);
        }
        if (nlpIntent == null || nlpIntent.isBlank()) {
            return new Translated(nlpIntent, payload);
        }
        String key = nlpIntent.trim().toLowerCase(Locale.ROOT);
        payload.putIfAbsent("nlp_intent", key);

        switch (key) {
            case "open_browser" -> {
                payload.putIfAbsent("url", defaultBrowserUrl);
                return new Translated("OPEN_URL", payload);
            }
            case "open_youtube" -> {
                payload.putIfAbsent("url", defaultYoutubeUrl);
                return new Translated("OPEN_URL", payload);
            }
            case "open_url" -> {
                return new Translated("OPEN_URL", payload);
            }
            case "open_ide" -> {
                payload.putIfAbsent("app", defaultIdeApp);
                return new Translated("OPEN_APP", payload);
            }
            case "open_terminal" -> {
                payload.putIfAbsent("app", defaultTerminalApp);
                return new Translated("OPEN_APP", payload);
            }
            case "open_app" -> {
                return new Translated("OPEN_APP", payload);
            }
            case "create_note", "create_local_note" -> {
                payload.putIfAbsent("title", "Заметка " + LocalDate.now().format(ISO_DATE));
                payload.putIfAbsent("body", "");
                return new Translated("CREATE_LOCAL_NOTE", payload);
            }
            case "minimize_window" -> {
                payload.putIfAbsent("title", payloadWindow(payload));
                return new Translated("FOCUS_WINDOW", payload);
            }
            case "show_notification" -> {
                payload.putIfAbsent("summary", "Jarvis");
                return new Translated("SHOW_NOTIFICATION", payload);
            }
            case "get_active_window" -> {
                return new Translated("GET_ACTIVE_WINDOW", payload);
            }
            default -> {
                log.debug("no translation for intent '{}', passing through", key);
                return new Translated(nlpIntent, payload);
            }
        }
    }

    private String payloadWindow(Map<String, Object> payload) {
        Object v = payload.get("window");
        if (v == null) v = payload.get("title");
        return v == null ? "" : v.toString();
    }

    public record Translated(String agentIntent, Map<String, Object> payload) {}
}
