package org.jarvis.voicegateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalIntentExecutionService {

    private final PcControlActionGateway pcControlActionGateway;
    private final SmartHomeActionGateway smartHomeActionGateway;

    public ExecutionResult execute(String action, Map<String, Object> parameters, String language, String correlationId, String userId) {
        String normalizedAction = normalizeAction(action);
        Map<String, Object> params = parameters == null ? Map.of() : Map.copyOf(parameters);
        return switch (normalizedAction) {
            case "greeting" -> responseOnly(action, language, "Привет, сэр.", "Hello, sir.");
            case "goodbye" -> responseOnly(action, language, "До связи, сэр.", "Until next time, sir.");
            case "thanks" -> responseOnly(action, language, "Всегда к вашим услугам, сэр.", "Always at your service, sir.");
            case "small_talk_jarvis", "wake_response" -> responseOnly(action, language, "Да, сэр.", "Yes, sir.");
            case "are_you_there" -> responseOnly(action, language, "Я здесь, сэр.", "I'm here, sir.");
            case "stt_timeout" -> responseOnly(action, language, "Сэр, я вас плохо расслышал.", "Sir, I couldn't hear you clearly.");
            case "stt_noise" -> responseOnly(action, language, "Похоже, это был только шум.", "That sounded like noise only.");
            case "welcome_home" -> responseOnly(action, language, "С возвращением, сэр.", "Welcome back, sir.");
            case "how_are_you" -> responseOnly(action, language, "Все системы в норме, сэр.", "All systems are nominal, sir.");
            case "what_doing" -> responseOnly(action, language, "Держу системы под контролем, сэр.", "Keeping the systems in order, sir.");
            case "bored" -> responseOnly(action, language, "Могу запустить музыку или игру, сэр.", "I can start music or a game, sir.");
            case "cheer_up" -> responseOnly(action, language, "Я рядом, сэр.", "I'm here, sir.");
            case "love_response" -> responseOnly(action, language, "Это взаимно, сэр.", "The feeling is mutual, sir.");
            case "play_music" -> dispatchPc(
                    action,
                    "PLAY_PAUSE",
                    Map.of(),
                    language,
                    correlationId,
                    userId,
                    "Запускаю музыку.",
                    "Starting the music.");
            case "play_radio" -> dispatchPc(
                    action,
                    "OPEN_URL",
                    Map.of("url", stringParam(params, "url", "https://radio.garden/")),
                    language,
                    correlationId,
                    userId,
                    "Открываю радио.",
                    "Opening the radio.");
            case "volume_up", "change_volume" -> dispatchPc(
                    action,
                    "VOLUME_UP",
                    Map.of("delta", intParam(params, 10, "delta", "amount")),
                    language,
                    correlationId,
                    userId,
                    "Делаю громче.",
                    "Turning the volume up.");
            case "volume_down" -> dispatchPc(
                    action,
                    "VOLUME_DOWN",
                    Map.of("delta", intParam(params, 10, "delta", "amount")),
                    language,
                    correlationId,
                    userId,
                    "Делаю тише.",
                    "Turning the volume down.");
            case "mute" -> dispatchPc(action, "MUTE", Map.of(), language, correlationId, userId, "Выключаю звук.", "Muting the audio.");
            case "unmute" -> dispatchPc(action, "UNMUTE", Map.of(), language, correlationId, userId, "Возвращаю звук.", "Restoring the audio.");
            case "set_volume" -> dispatchPc(
                    action,
                    "SET_VOLUME",
                    Map.of("level", intParam(params, 100, "level")),
                    language,
                    correlationId,
                    userId,
                    "Устанавливаю громкость.",
                    "Setting the volume.");
            case "play", "resume", "media_toggle" -> dispatchPc(action, "PLAY_PAUSE", Map.of(), language, correlationId, userId, "Продолжаю воспроизведение.", "Resuming playback.");
            case "pause", "stop" -> dispatchPc(action, "PAUSE", Map.of(), language, correlationId, userId, "Ставлю на паузу.", "Pausing playback.");
            case "next", "next_track", "media_next" -> dispatchPc(action, "NEXT", Map.of(), language, correlationId, userId, "Следующий трек.", "Skipping to the next track.");
            case "previous", "prev", "previous_track", "media_prev" -> dispatchPc(action, "PREV", Map.of(), language, correlationId, userId, "Предыдущий трек.", "Going back to the previous track.");
            case "open_app", "launch_app" -> dispatchPc(
                    action,
                    "OPEN_APP",
                    Map.of("app", stringParam(params, "app", "browser")),
                    language,
                    correlationId,
                    userId,
                    "Открываю приложение.",
                    "Opening the application.");
            case "open_browser" -> dispatchPc(action, "OPEN_APP", Map.of("app", "browser"), language, correlationId, userId, "Открываю браузер.", "Opening the browser.");
            case "open_youtube" -> dispatchPc(action, "OPEN_APP", Map.of("app", "youtube"), language, correlationId, userId, "Открываю YouTube.", "Opening YouTube.");
            case "open_ide", "open_code", "open_notepad" -> dispatchPc(action, "OPEN_APP", Map.of("app", stringParam(params, "ide", "code")), language, correlationId, userId, "Открываю редактор.", "Opening the editor.");
            case "open_terminal" -> dispatchPc(action, "OPEN_APP", Map.of("app", "terminal"), language, correlationId, userId, "Открываю терминал.", "Opening the terminal.");
            case "open_url", "open_news" -> dispatchPc(
                    action,
                    "OPEN_URL",
                    Map.of("url", stringParam(params, "url", "https://news.google.com/")),
                    language,
                    correlationId,
                    userId,
                    "Открываю ссылку.",
                    "Opening the link.");
            case "work_mode" -> scenario(action, "work", language, correlationId, userId, "Включаю рабочий режим.", "Activating work mode.");
            case "rest_mode", "relax_mode" -> scenario(action, "rest", language, correlationId, userId, "Включаю режим отдыха.", "Activating rest mode.");
            case "focus_mode" -> scenario(action, "focus", language, correlationId, userId, "Включаю режим фокуса.", "Activating focus mode.");
            case "house_party", "party_mode", "protocol_house_party" -> scenario(action, "party", language, correlationId, userId, "Запускаю режим вечеринки.", "Activating party mode.");
            case "clean_slate", "shutdown_mode", "protocol_clean_slate" -> scenario(action, "clean_slate", language, correlationId, userId, "Запускаю сценарий очистки.", "Running the clean slate scenario.");
            case "protocol_cozy_evening" -> scenario(action, "cozy_evening", language, correlationId, userId, "Запускаю уютный вечер.", "Activating cozy evening.");
            case "protocol_guests" -> scenario(action, "guests", language, correlationId, userId, "Запускаю режим гостей.", "Activating guest mode.");
            case "protocol_holiday" -> scenario(action, "holiday", language, correlationId, userId, "Запускаю праздничный режим.", "Activating holiday mode.");
            case "game_mode" -> scenario(action, "game", language, correlationId, userId, "Запускаю игровой режим.", "Activating game mode.");
            case "protocol_morning" -> scenario(action, "morning", language, correlationId, userId, "Запускаю утренний режим.", "Activating morning mode.");
            case "protocol_leaving" -> scenario(action, "leaving", language, correlationId, userId, "Запускаю режим ухода.", "Activating leaving mode.");
            case "protocol_panic" -> scenario(action, "panic", language, correlationId, userId, "Запускаю тревожный сценарий.", "Activating panic mode.");
            case "minimize_window", "window_minimize" -> dispatchPc(action, "MINIMIZE", Map.of(), language, correlationId, userId, "Сворачиваю окно.", "Minimizing the window.");
            case "maximize_window", "window_maximize" -> dispatchPc(action, "MAXIMIZE", Map.of(), language, correlationId, userId, "Разворачиваю окно.", "Maximizing the window.");
            case "lock_screen" -> dispatchPc(action, "LOCK_SCREEN", Map.of(), language, correlationId, userId, "Блокирую экран.", "Locking the screen.");
            case "clipboard_copy" -> hotkey(action, "ctrl+c", language, correlationId, userId, "Копирую.", "Copying.");
            case "clipboard_paste" -> hotkey(action, "ctrl+v", language, correlationId, userId, "Вставляю.", "Pasting.");
            case "undo_action" -> hotkey(action, "ctrl+z", language, correlationId, userId, "Отменяю последнее действие.", "Undoing the last action.");
            case "switch_window" -> hotkey(action, "Alt+Tab", language, correlationId, userId, "Переключаю окно.", "Switching windows.");
            case "close_window" -> hotkey(action, "Alt+F4", language, correlationId, userId, "Закрываю окно.", "Closing the window.");
            case "fullscreen" -> hotkey(action, "F11", language, correlationId, userId, "Переключаю полноэкранный режим.", "Toggling fullscreen.");
            case "refresh_page" -> hotkey(action, "F5", language, correlationId, userId, "Обновляю страницу.", "Refreshing the page.");
            case "navigate_back" -> hotkey(action, "Alt+Left", language, correlationId, userId, "Возвращаюсь назад.", "Going back.");
            case "navigate_forward" -> hotkey(action, "Alt+Right", language, correlationId, userId, "Перехожу вперед.", "Going forward.");
            case "show_desktop" -> hotkey(action, "Super+d", language, correlationId, userId, "Показываю рабочий стол.", "Showing the desktop.");
            case "open_settings" -> dispatchPc(action, "OPEN_APP", Map.of("app", "settings"), language, correlationId, userId, "Открываю настройки.", "Opening settings.");
            case "system_search" -> hotkey(action, "Super_L", language, correlationId, userId, "Открываю системный поиск.", "Opening system search.");
            case "switch_language" -> hotkey(action, "Alt+Shift", language, correlationId, userId, "Переключаю язык.", "Switching the language.");
            case "screenshot" -> hotkey(action, "Print", language, correlationId, userId, "Делаю скриншот.", "Taking a screenshot.");
            case "sleep_mode" -> systemCommand(action, "sleep", language, correlationId, userId, "Перевожу систему в сон.", "Putting the system to sleep.");
            case "monitor_off" -> systemCommand(action, "monitor_off", language, correlationId, userId, "Гашу экран.", "Turning the screen off.");
            case "network_check" -> dispatchPc(action, "OPEN_URL", Map.of("url", stringParam(params, "url", "https://fast.com/")), language, correlationId, userId, "Открываю проверку сети.", "Opening the network check.");
            case "find_in_page" -> hotkey(action, "ctrl+f", language, correlationId, userId, "Открываю поиск по странице.", "Opening find in page.");
            case "focus_address_bar" -> hotkey(action, "ctrl+l", language, correlationId, userId, "Фокусирую адресную строку.", "Focusing the address bar.");
            case "rename_item" -> hotkey(action, "F2", language, correlationId, userId, "Переименовываю объект.", "Renaming the item.");
            case "delete_selection" -> hotkey(action, "Delete", language, correlationId, userId, "Удаляю выделение.", "Deleting the selection.");
            case "press_enter" -> hotkey(action, "Return", language, correlationId, userId, "Подтверждаю.", "Confirming.");
            case "smart_home_action" -> executeSmartHome(action, params, language, correlationId, userId);
            default -> ExecutionResult.unsupported(
                    action,
                    respond(language, "Эта команда требует оркестратора или недоступной возможности.", "That command needs the orchestrator or an unavailable capability."),
                    "LOCAL_FALLBACK_UNSUPPORTED");
        };
    }

    private ExecutionResult scenario(String action, String scenario, String language, String correlationId, String userId, String ru, String en) {
        return dispatchPc(action, "SCENARIO", Map.of("name", scenario), language, correlationId, userId, ru, en);
    }

    private ExecutionResult hotkey(String action, String keyCombination, String language, String correlationId, String userId, String ru, String en) {
        return dispatchPc(action, "HOTKEY", Map.of("keyCombination", keyCombination), language, correlationId, userId, ru, en);
    }

    private ExecutionResult systemCommand(String action, String command, String language, String correlationId, String userId, String ru, String en) {
        return dispatchPc(action, "SYSTEM_COMMAND", Map.of("command", command), language, correlationId, userId, ru, en);
    }

    private ExecutionResult executeSmartHome(String action, Map<String, Object> params, String language, String correlationId, String userId) {
        String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
        String deviceId = stringParam(params, "deviceId", null);
        String deviceAction = stringParam(params, "action", null);
        Object payload = params.get("payload");
        if (deviceId == null || deviceAction == null) {
            return ExecutionResult.failed(
                    action,
                    respond(language, "Недостаточно данных для управления умным домом.", "Smart-home command parameters are incomplete."),
                    true,
                    false,
                    false,
                    true,
                    "SMART_HOME_PARAMETERS_INVALID");
        }
        try {
            smartHomeActionGateway.execute(scopedUserId, deviceId, deviceAction, payload);
            return ExecutionResult.success(
                    action,
                    respond(language, "Команда умного дома отправлена.", "Smart-home command sent."),
                    true,
                    true,
                    true,
                    false,
                    null);
        } catch (RuntimeException e) {
            log.warn("Local smart-home fallback failed: action={}, correlationId={}, error={}", action, correlationId, e.getMessage());
            return ExecutionResult.failed(
                    action,
                    respond(language, "Возможность умного дома недоступна.", "The smart-home capability is unavailable."),
                    true,
                    true,
                    true,
                    true,
                    "SMART_HOME_CAPABILITY_UNAVAILABLE: " + e.getMessage());
        }
    }

    private ExecutionResult dispatchPc(
            String action,
            String pcAction,
            Map<String, Object> params,
            String language,
            String correlationId,
            String userId,
            String ruResponse,
            String enResponse) {
        try {
            PcControlActionGateway.DispatchResult dispatchResult =
                    pcControlActionGateway.dispatch(pcAction, new LinkedHashMap<>(params), userId, correlationId);
            String responseText = dispatchResult.executionSucceeded()
                    ? respond(language, ruResponse, enResponse)
                    : respond(language, "Не удалось выполнить команду локально.", "I couldn't execute that command locally.");
            return new ExecutionResult(
                    action,
                    responseText,
                    true,
                    dispatchResult.executorFound(),
                    dispatchResult.executionAttempted(),
                    dispatchResult.executionSucceeded(),
                    dispatchResult.executionFailed(),
                    dispatchResult.failureReason());
        } catch (RuntimeException e) {
            log.warn("Local PC fallback failed: action={}, pcAction={}, correlationId={}, error={}",
                    action, pcAction, correlationId, e.getMessage());
            return ExecutionResult.failed(
                    action,
                    respond(language, "Локальное выполнение команды недоступно.", "Local execution for that command is unavailable."),
                    true,
                    false,
                    false,
                    true,
                    "LOCAL_PC_FALLBACK_FAILED: " + e.getMessage());
        }
    }

    private ExecutionResult responseOnly(String action, String language, String ru, String en) {
        return ExecutionResult.success(action, respond(language, ru, en), true, false, false, false, null);
    }

    private String respond(String language, String ru, String en) {
        String normalized = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return normalized.startsWith("en") ? en : ru;
    }

    private int intParam(Map<String, Object> params, int fallback, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value == null) {
                continue;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private String stringParam(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "unknown";
        }
        return action.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public record ExecutionResult(
            String action,
            String responseText,
            boolean actionResolved,
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason) {

        static ExecutionResult success(
                String action,
                String responseText,
                boolean actionResolved,
                boolean executorFound,
                boolean executionAttempted,
                boolean executionFailed,
                String failureReason) {
            return new ExecutionResult(
                    action,
                    responseText,
                    actionResolved,
                    executorFound,
                    executionAttempted,
                    true,
                    executionFailed,
                    failureReason);
        }

        static ExecutionResult failed(
                String action,
                String responseText,
                boolean actionResolved,
                boolean executorFound,
                boolean executionAttempted,
                boolean executionFailed,
                String failureReason) {
            return new ExecutionResult(
                    action,
                    responseText,
                    actionResolved,
                    executorFound,
                    executionAttempted,
                    false,
                    executionFailed,
                    failureReason);
        }

        static ExecutionResult unsupported(String action, String responseText, String failureReason) {
            return new ExecutionResult(action, responseText, false, false, false, false, true, failureReason);
        }
    }
}
