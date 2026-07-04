package org.jarvis.voicegateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * US-108 (onboarding "ты можешь сказать…") + US-106 (tactical options).
 *
 * <p>A read-only capability index so the owner can ask "Jarvis, what can you do?"
 * and the UI / voice client can surface example phrases. The catalogue is curated
 * from the REAL intents the stack resolves (RuleBasedNlpService + IntentRiskCatalog):
 * LOW-risk intents run after intent resolution; MEDIUM-risk intents require the
 * confirmation gate. No state, no auth-sensitive data — purely informational.
 */
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

    private static final List<Category> CATEGORIES = List.of(
            new Category("Звук", "🔊", List.of(
                    new Command("volume_down", "LOW", false, List.of("сделай тише", "turn it down")),
                    new Command("volume_up", "LOW", false, List.of("сделай громче", "louder")),
                    new Command("mute", "LOW", false, List.of("выключи звук", "mute")))),
            new Category("Окна и приложения", "🪟", List.of(
                    new Command("open_app", "LOW", false, List.of("открой браузер", "open the terminal")),
                    new Command("open_url", "LOW", false, List.of("открой Grafana", "open the logs")),
                    new Command("focus_window", "LOW", false, List.of("переключись на терминал", "focus the editor")))),
            new Category("Экран", "🖥", List.of(
                    new Command("screenshot", "MEDIUM", true, List.of("сделай скриншот", "take a screenshot")),
                    new Command("lock_screen", "MEDIUM", true, List.of("заблокируй экран", "lock the screen")))),
            new Category("Планы и память", "📝", List.of(
                    new Command("add_reminder", "LOW", false, List.of("напомни купить кофе", "remind me to test")),
                    new Command("create_local_note", "LOW", false, List.of("запомни что я люблю эспрессо", "remember this")),
                    new Command("get_time", "LOW", false, List.of("который час", "what time is it")))),
            new Category("Финансы", "💸", List.of(
                    new Command("add_expense", "LOW", false, List.of("потратил 500 на обед", "add expense 20 lunch")))),
            new Category("Умный дом", "🏠", List.of(
                    new Command("home.light.off", "LOW", false, List.of("выключи свет на кухне", "lights off")),
                    new Command("scene.activate", "LOW", false, List.of("включи сцену кино", "movie night")))));

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
                CATEGORIES,
                TACTICAL);
    }
}
