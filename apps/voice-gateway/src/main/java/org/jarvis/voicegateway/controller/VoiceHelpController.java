package org.jarvis.voicegateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * US-108 (onboarding "ты можешь сказать…") + US-106 (tactical options).
 *
 * <p>A read-only capability index so the owner can ask "Jarvis, what can you do?"
 * and the UI / voice client can surface example phrases. The catalogue is curated
 * from the REAL intents the stack resolves — names mirror either this module's own
 * local fallback executor ({@code LocalIntentExecutionService}, e.g. {@code volume_up},
 * {@code open_browser}, {@code play}/{@code pause}) or the orchestrator's
 * {@code IntentRiskCatalog} entries for intents routed downstream (e.g.
 * {@code planner.create-task}, {@code memory.search}, {@code home.light.on}).
 * LOW-risk intents run after intent resolution; MEDIUM-risk intents require the
 * confirmation gate. No state, no auth-sensitive data — purely informational.
 *
 * <p>P0 fix: the catalog previously omitted whole categories (task management,
 * memory search, media playback, "ask the brain", general help) and, more
 * importantly, had no guard against ever serving an empty list. {@link #help()}
 * now routes through {@link #resolveCategories(List)}, which guarantees a
 * non-empty, sensible static fallback even if the curated catalog above were
 * ever emptied out by a future refactor (e.g. wiring this from a live intent
 * source that fails to respond).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceHelpController {

    public record Command(String intent, String risk, boolean needsConfirmation, List<String> say) {}

    public record Category(String title, String icon, List<Command> commands) {}

    public record HelpResponse(
            String assistant,
            String hint,
            List<Category> categories,
            List<String> tactical) {}

    private static final List<Category> KNOWN_CATEGORIES = List.of(
            new Category("Звук", "🔊", List.of(
                    new Command("volume_up", "LOW", false, List.of("сделай громче", "turn it up", "louder")),
                    new Command("volume_down", "LOW", false, List.of("сделай тише", "turn it down", "lower the volume")),
                    new Command("mute", "LOW", false, List.of("выключи звук", "mute the audio")),
                    new Command("unmute", "LOW", false, List.of("включи звук", "unmute")))),
            new Category("Воспроизведение", "🎵", List.of(
                    new Command("play", "LOW", false, List.of("продолжи воспроизведение", "play the music", "resume")),
                    new Command("pause", "LOW", false, List.of("поставь на паузу", "pause the music", "stop")))),
            new Category("Окна и приложения", "🪟", List.of(
                    new Command("open_browser", "LOW", false, List.of("открой браузер", "open the browser")),
                    new Command("open_terminal", "LOW", false, List.of("открой терминал", "open the terminal")),
                    new Command("open_url", "LOW", false, List.of("открой Grafana", "open the logs")),
                    new Command("focus_window", "LOW", false, List.of("переключись на терминал", "focus the editor")))),
            new Category("Экран", "🖥", List.of(
                    new Command("screenshot", "MEDIUM", true, List.of("сделай скриншот", "take a screenshot")),
                    new Command("lock_screen", "MEDIUM", true, List.of("заблокируй экран", "lock the screen")))),
            new Category("Планы и память", "📝", List.of(
                    new Command("planner.create-task", "LOW", false, List.of("добавь задачу купить молоко", "add a task to buy milk")),
                    new Command("add_reminder", "LOW", false, List.of("напомни купить кофе", "remind me to test")),
                    new Command("create_local_note", "LOW", false, List.of("запомни что я люблю эспрессо", "remember this")),
                    new Command("memory.search", "LOW", false, List.of("что помнишь про отпуск в Италии", "what do you remember about my trip")),
                    new Command("get_time", "LOW", false, List.of("который час", "what time is it")))),
            new Category("Финансы", "💸", List.of(
                    new Command("add_expense", "LOW", false, List.of("потратил 500 на обед", "add expense 20 lunch")))),
            new Category("Умный дом", "🏠", List.of(
                    new Command("home.light.on", "LOW", false, List.of("включи свет на кухне", "turn on the kitchen lights")),
                    new Command("home.light.off", "LOW", false, List.of("выключи свет на кухне", "lights off")),
                    new Command("scene.activate", "LOW", false, List.of("включи сцену кино", "movie night")))),
            new Category("Вопросы к мозгу", "🧠", List.of(
                    new Command("ask_brain", "LOW", false, List.of("какая погода в Лондоне", "what is the capital of France")))),
            new Category("Помощь", "❓", List.of(
                    new Command("help", "LOW", false, List.of("что ты умеешь", "what can I say")))));

    /**
     * Guaranteed non-empty fallback. Only served if {@link #KNOWN_CATEGORIES}
     * ever resolves null/empty — the owner must never see a blank "what can I
     * say" screen just because an upstream intent source is unavailable.
     */
    private static final List<Category> FALLBACK_CATEGORIES = List.of(
            new Category("Основное", "❓", List.of(
                    new Command("help", "LOW", false, List.of("что ты умеешь", "what can I say")),
                    new Command("open_browser", "LOW", false, List.of("открой браузер", "open the browser")),
                    new Command("volume_down", "LOW", false, List.of("сделай тише", "turn it down")))));

    private static final List<String> TACTICAL = List.of(
            "Спроси «что на экране?» — Jarvis опишет активное окно (OCR).",
            "Рискованные действия (скриншот/блокировка) Jarvis выполнит только после подтверждения.",
            "Скажи «статус» — получишь состояние мозга, голоса, памяти и PC-control.",
            "Всё локально: голос, мозг и память работают без интернета.");

    @GetMapping("/help")
    public HelpResponse help() {
        return new HelpResponse(
                "J.A.R.V.I.S.",
                "Ты можешь сказать, сэр:",
                resolveCategories(KNOWN_CATEGORIES),
                TACTICAL);
    }

    /**
     * Resolves the categories to serve, never returning an empty list even if
     * {@code source} is null or empty. Package-visible so tests can exercise
     * the fallback path directly without needing to break the curated catalog.
     */
    static List<Category> resolveCategories(List<Category> source) {
        if (source == null || source.isEmpty()) {
            log.warn("Voice help catalog source was empty/unavailable; serving static fallback");
            return FALLBACK_CATEGORIES;
        }
        return source;
    }
}
