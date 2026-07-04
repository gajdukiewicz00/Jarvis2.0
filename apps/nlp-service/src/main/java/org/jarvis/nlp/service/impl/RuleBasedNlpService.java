package org.jarvis.nlp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.NlpService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class RuleBasedNlpService implements NlpService {

    private static final int RXF = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // ============ GREETINGS ============
    private static final Pattern HELLO = Pattern.compile(
            "(?:^|\\b)(?:привет|здравствуй|здорово|добрый\\s+(?:день|вечер|утро)|хай|хелло)\\b", RXF);

    private static final Pattern GOODBYE = Pattern.compile(
            "(?:^|\\b)(?:пока|до свидания|прощай|до встречи|бай)\\b", RXF);

    private static final Pattern THANKS = Pattern.compile(
            "(?:^|\\b)(?:спасибо|благодарю)\\b", RXF);

    // ============ TIMER ============
    private static final Pattern TIMER_FULL = Pattern.compile(
            "(?:^|\\b)(?:(поставь|установи|заведи|постави)\\s+)?таймер(?:\\s+на)?\\s+([\\p{L}\\d]+)\\s*(секунд(?:у|ы)?|сек|с|минут(?:у|ы)?|мин)\\b",
            RXF);

    private static final Pattern TIMER_SHORT = Pattern.compile(
            "(?:^|\\b)таймер\\s+([\\p{L}\\d]+)(?:\\b|$)", RXF);

    // ============ VOLUME ============
    private static final Pattern VOL_UP = Pattern.compile(
            "(?:^|\\b)(?:(?:прибавь|увеличь|подними|погромче|громче)(?:\\s+(?:громкость|звук))?|(?:volume\\s+up)|(?:turn\\s+(?:the\\s+)?(?:volume|sound)\\s+up)|(?:(?:increase|raise|boost)\\s+(?:the\\s+)?(?:volume|sound))|(?:make(?:\\s+it)?\\s+louder))(?:\\s+(?:на|by)\\s+([\\p{L}\\d]+))?(?:\\s*%)?(?:\\b|$)",
            RXF);

    private static final Pattern VOL_DOWN = Pattern.compile(
            "(?:^|\\b)(?:(?:уменьши|убавь|снизь|понизь|потише|тише)(?:\\s+(?:громкость|звук))?|(?:volume\\s+down)|(?:turn\\s+(?:the\\s+)?(?:volume|sound)\\s+down)|(?:(?:decrease|lower|reduce)\\s+(?:the\\s+)?(?:volume|sound))|(?:make(?:\\s+it)?\\s+quieter)|quieter)(?:\\s+(?:на|by)\\s+([\\p{L}\\d]+))?(?:\\s*%)?(?:\\b|$)",
            RXF);

    private static final Pattern VOL_SET = Pattern.compile(
            "(?:^|\\b)(?:громкость|звук)\\s+(?:на\\s+)?([\\p{L}\\d]+)(?:\\s*%)?(?:\\b|$)", RXF);

    private static final Pattern MUTE = Pattern.compile(
            "(?:^|\\b)(?:выключи|отключи|убери)\\s+(?:звук|громкость)|замолчи|mute\\b", RXF);

    private static final Pattern UNMUTE = Pattern.compile(
            "(?:^|\\b)(?:включи|верни)\\s+(?:звук|громкость)|unmute\\b", RXF);

    // ============ MEDIA CONTROL ============
    // Require an explicit media object — bare "продолжи"/"play" are dropped because
    // Vosk noise transcribed as them used to misfire a blind PLAY_PAUSE (media auto-start).
    private static final Pattern PLAY = Pattern.compile(
            "(?:^|\\b)(?:играй|воспроизводи|запусти\\s+музыку"
                    + "|продолжи\\s+(?:воспроизведение|музыку|трек|плеер|подкаст)"
                    + "|play\\s+(?:music|track|song))\\b",
            RXF);

    private static final Pattern PAUSE = Pattern.compile(
            "(?:^|\\b)(?:пауза|останови|pause|поставь\\s+на\\s+паузу)\\b", RXF);

    // B1 — barge-in / cancel. Matched FIRST so "стоп"/"stop"/"отмена"/"заткнись"
    // cancel the active voice session rather than pausing media.
    private static final Pattern CANCEL = Pattern.compile(
            "(?:^|\\b)(?:отмена|отмени|прекрати|стоп|стой|заткнись|джарвис\\s+стоп"
                    + "|cancel|abort|stop|jarvis\\s+stop|hush)\\b", RXF);

    // B3 — energy-aware planning intents.
    private static final Pattern PLAN_BY_ENERGY = Pattern.compile(
            "(?:^|\\b)(?:спланируй\\s+день\\s+по\\s+энергии|план(?:ируй)?\\s+по\\s+энергии"
                    + "|plan\\s+(?:my\\s+)?day\\s+by\\s+energy)\\b", RXF);
    private static final Pattern WHAT_NOW = Pattern.compile(
            "(?:^|\\b)(?:что\\s+мне\\s+делать(?:\\s+сейчас)?|что\\s+делать\\s+сейчас"
                    + "|what\\s+should\\s+i\\s+do(?:\\s+now)?)\\b", RXF);
    private static final Pattern ENERGY_SET = Pattern.compile(
            "(?:^|\\b)(?:я\\s+устал|я\\s+выжат|вымотан|нет\\s+сил|полон\\s+сил|я\\s+бодр"
                    + "|у\\s+меня\\s+норм(?:альн\\w*)?\\s+энерги\\w*|i'?m\\s+tired|i'?m\\s+exhausted|i'?m\\s+energized)\\b",
            RXF);

    private static final Pattern NEXT_TRACK = Pattern.compile(
            "(?:^|\\b)(?:следующий|дальше|next|вперед)(?:\\s+(?:трек|песня|песню))?\\b", RXF);

    private static final Pattern PREV_TRACK = Pattern.compile(
            "(?:^|\\b)(?:предыдущий|назад|previous|prev)(?:\\s+(?:трек|песня|песню))?\\b", RXF);

    // ============ APPS ============
    private static final Pattern OPEN_BROWSER = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:браузер|chrome|firefox|хром|browser)\\b", RXF);

    private static final Pattern OPEN_YOUTUBE = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:ютуб|youtube|ютьюб)\\b", RXF);

    private static final Pattern OPEN_IDE = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:ide|idea|intellij|code|vscode|vs code|инталидж|интеллидж|редактор)\\b",
            RXF);

    private static final Pattern OPEN_TELEGRAM = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:телеграм|telegram|мессенджер)\\b", RXF);

    private static final Pattern OPEN_SPOTIFY = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:спотифай|spotify|музыку)\\b", RXF);

    private static final Pattern OPEN_TERMINAL = Pattern.compile(
            "(?:^|\\b)(?:открой|запусти|включи)\\s+(?:терминал|terminal|консоль|shell)\\b", RXF);

    // ============ NOTES (P2 Jarvis Loop) ============
    private static final Pattern CREATE_NOTE = Pattern.compile(
            "(?:^|\\b)(?:(?:создай|сделай|запиши|добавь)\\s+(?:заметку|запись|note)|создай\\s+note|create\\s+(?:a\\s+)?note|make\\s+(?:a\\s+)?note|take\\s+(?:a\\s+)?note)(?:\\s+(?:на|про|о|об|about|on|for)\\s+(.+))?",
            RXF);

    private static final Pattern CREATE_NOTE_TODAY = Pattern.compile(
            "(?:^|\\b)(?:(?:создай|сделай|запиши)\\s+(?:заметку|запись)\\s+(?:на|за)\\s+(?:сегодня|сегодняшний\\s+день)|note\\s+for\\s+today|today'?s\\s+note)\\b",
            RXF);

    // ============ SCENARIOS ============
    private static final Pattern WORK_MODE = Pattern.compile(
            "(?:^|\\b)(?:режим\\s+работы|рабочий\\s+режим|work\\s*mode|включи\\s+работу)\\b", RXF);

    private static final Pattern REST_MODE = Pattern.compile(
            "(?:^|\\b)(?:режим\\s+отдыха|отдыхаю|rest\\s*mode|relax|расслабься|включи\\s+отдых)\\b", RXF);

    private static final Pattern FOCUS_MODE = Pattern.compile(
            "(?:^|\\b)(?:режим\\s+фокус(?:а|ировки)?|focus\\s*mode|не\\s+беспокоить|тихий\\s+режим)\\b", RXF);

    // ============ WINDOW CONTROL ============
    private static final Pattern MINIMIZE = Pattern.compile(
            "(?:^|\\b)(?:сверни|минимизируй|убери)(?:\\s+(?:окно|это))?\\b", RXF);

    private static final Pattern MAXIMIZE = Pattern.compile(
            "(?:^|\\b)(?:разверни|максимизируй|на\\s+весь\\s+экран)(?:\\s+(?:окно|это))?\\b", RXF);

    private static final Pattern LOCK_SCREEN = Pattern.compile(
            "(?:^|\\b)(?:заблокируй|залочь|lock)(?:\\s+(?:экран|компьютер|комп))?\\b", RXF);

    private static final Pattern TEMPERATURE_VALUE = Pattern.compile("\\b(1[6-9]|2\\d|30)(?:[\\.,](\\d))?\\b");

    private static final Pattern NUM_TOKEN = Pattern.compile("\\d+");

    private static final Map<String, Integer> RUS_NUM = buildRusNumbers();
    private static final Map<String, List<String>> SMART_HOME_DEVICE_ALIASES = buildSmartHomeDeviceAliases();
    private static final List<String> SMART_HOME_ON_KEYWORDS = List.of(
            "включи", "вруби", "зажги", "turn on", "switch on");
    private static final List<String> SMART_HOME_OFF_KEYWORDS = List.of(
            "выключи", "отключи", "погаси", "turn off", "switch off");
    private static final List<String> SMART_HOME_TOGGLE_KEYWORDS = List.of(
            "переключи", "toggle");
    private static final List<String> SMART_HOME_SET_KEYWORDS = List.of(
            "установи", "поставь", "сделай", "set", "adjust");

    @Override
    public NlpResult infer(String text, String languageCode) {
        if (text == null)
            text = "";
        String norm = TextNormalizer.normalize(text);

        // B1 — cancel / barge-in takes precedence over every other intent.
        if (CANCEL.matcher(norm).find()) {
            return new NlpResult("cancel", Map.of());
        }

        // B3 — energy-aware planning.
        if (PLAN_BY_ENERGY.matcher(norm).find()) {
            return new NlpResult("plan_by_energy", Map.of());
        }
        if (ENERGY_SET.matcher(norm).find()) {
            return new NlpResult("set_energy", Map.of("level", norm));
        }
        if (WHAT_NOW.matcher(norm).find()) {
            return new NlpResult("what_now", Map.of());
        }

        // Greetings
        if (HELLO.matcher(norm).find()) {
            return new NlpResult("hello", Map.of());
        }
        if (GOODBYE.matcher(norm).find()) {
            return new NlpResult("goodbye", Map.of());
        }
        if (THANKS.matcher(norm).find()) {
            return new NlpResult("thanks", Map.of());
        }

        // Notes — must run before VOL_UP/VOL_DOWN because "сделай заметку" otherwise
        // hits the volume regex's bare "сделай" alternative.
        Matcher mntop = CREATE_NOTE_TODAY.matcher(norm);
        if (mntop.find()) {
            return new NlpResult("create_note", Map.of("scope", "today"));
        }
        Matcher mntoptopic = CREATE_NOTE.matcher(norm);
        if (mntoptopic.find()) {
            String topic = mntoptopic.groupCount() >= 1 ? mntoptopic.group(1) : null;
            Map<String, String> slots = new HashMap<>();
            if (topic != null && !topic.isBlank()) {
                slots.put("topic", topic.trim());
            }
            return new NlpResult("create_note", slots);
        }

        // Timer
        Matcher mt = TIMER_FULL.matcher(norm);
        if (mt.find()) {
            String amountTok = mt.group(2);
            String unitTok = mt.group(3);
            Integer amount = parseNumber(amountTok);
            if (amount != null && amount > 0) {
                String unit = isSeconds(unitTok) ? "sec" : "min";
                Map<String, String> slots = new HashMap<>();
                slots.put("amount", String.valueOf(amount));
                slots.put("unit", unit);
                return new NlpResult("set_timer", slots);
            }
        }

        mt = TIMER_SHORT.matcher(norm);
        if (mt.find()) {
            Integer amount = parseNumber(mt.group(1));
            if (amount != null && amount > 0) {
                Map<String, String> slots = new HashMap<>();
                slots.put("amount", String.valueOf(amount));
                slots.put("unit", "min");
                return new NlpResult("set_timer", slots);
            }
        }

        // Volume controls
        if (MUTE.matcher(norm).find()) {
            return new NlpResult("mute", Map.of());
        }
        if (UNMUTE.matcher(norm).find()) {
            return new NlpResult("unmute", Map.of());
        }

        Matcher mu = VOL_UP.matcher(norm);
        if (mu.find()) {
            Integer delta = parseNumber(mu.group(1));
            if (delta == null || delta <= 0)
                delta = 10;
            Map<String, String> slots = new HashMap<>();
            slots.put("amount", String.valueOf(delta));
            slots.put("direction", "+");
            return new NlpResult("volume_up", slots);
        }

        Matcher md = VOL_DOWN.matcher(norm);
        if (md.find()) {
            Integer delta = parseNumber(md.group(1));
            if (delta == null || delta <= 0)
                delta = 10;
            Map<String, String> slots = new HashMap<>();
            slots.put("amount", String.valueOf(delta));
            slots.put("direction", "-");
            return new NlpResult("volume_down", slots);
        }

        Matcher mon = VOL_SET.matcher(norm);
        if (mon.find()) {
            Integer level = parseNumber(mon.group(1));
            if (level != null && level > 0) {
                Map<String, String> slots = new HashMap<>();
                slots.put("level", String.valueOf(level));
                return new NlpResult("volume_set", slots);
            }
        }

        // Media controls
        if (PLAY.matcher(norm).find()) {
            return new NlpResult("play", Map.of());
        }
        if (PAUSE.matcher(norm).find()) {
            return new NlpResult("pause", Map.of());
        }
        if (NEXT_TRACK.matcher(norm).find()) {
            return new NlpResult("next_track", Map.of());
        }
        if (PREV_TRACK.matcher(norm).find()) {
            return new NlpResult("previous_track", Map.of());
        }

        // Apps
        if (OPEN_BROWSER.matcher(norm).find()) {
            return new NlpResult("open_browser", Map.of("app", "browser"));
        }
        if (OPEN_YOUTUBE.matcher(norm).find()) {
            return new NlpResult("open_youtube", Map.of("app", "youtube"));
        }
        if (OPEN_IDE.matcher(norm).find()) {
            return new NlpResult("open_ide", Map.of("app", "idea"));
        }
        if (OPEN_TELEGRAM.matcher(norm).find()) {
            return new NlpResult("open_app", Map.of("app", "telegram"));
        }
        if (OPEN_SPOTIFY.matcher(norm).find()) {
            return new NlpResult("open_app", Map.of("app", "spotify"));
        }
        if (OPEN_TERMINAL.matcher(norm).find()) {
            return new NlpResult("open_app", Map.of("app", "terminal"));
        }

        NlpResult smartHomeResult = inferSmartHome(norm);
        if (smartHomeResult != null) {
            return smartHomeResult;
        }

        // Scenarios
        if (WORK_MODE.matcher(norm).find()) {
            return new NlpResult("work_mode", Map.of());
        }
        if (REST_MODE.matcher(norm).find()) {
            return new NlpResult("rest_mode", Map.of());
        }
        if (FOCUS_MODE.matcher(norm).find()) {
            return new NlpResult("focus_mode", Map.of());
        }

        // Window control
        if (MINIMIZE.matcher(norm).find()) {
            return new NlpResult("minimize_window", Map.of());
        }
        if (MAXIMIZE.matcher(norm).find()) {
            return new NlpResult("maximize_window", Map.of());
        }
        if (LOCK_SCREEN.matcher(norm).find()) {
            return new NlpResult("lock_screen", Map.of());
        }

        int rxf = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

        // Screenshot
        if (Pattern.compile("(?:^|\\b)(?:скриншот|снимок\\s+экрана|сделай\\s+скрин|сними\\s+экран|screenshot|screen\\s*shot)\\b", rxf)
                .matcher(norm).find()) {
            return new NlpResult("screenshot", Map.of());
        }

        // Lock screen
        if (Pattern.compile("(?:^|\\b)(?:заблокируй|заблокировать|залочь|lock)(?:\\s+(?:экран|компьютер|screen))?\\b", rxf)
                .matcher(norm).find()) {
            return new NlpResult("lock_screen", Map.of());
        }

        // Expense logging
        Matcher mExp = Pattern.compile(
                "(?:^|\\b)(?:потратил[аи]?|истратил[аи]?|купил[аи]?|расход)\\b.*?(\\d+)\\s*"
                        + "(?:руб|р|₽|евро|eur|€|долл|usd|\\$)?(?:\\s+(?:на|в|за)\\s+([\\p{L}][\\p{L}\\s]*?))?(?:\\b|$)",
                rxf).matcher(norm);
        if (mExp.find()) {
            Map<String, String> slots = new HashMap<>();
            Integer amount = parseNumber(mExp.group(1));
            if (amount != null) {
                slots.put("amount", String.valueOf(amount));
            }
            String category = mExp.group(2);
            slots.put("category", category != null && !category.isBlank() ? category.trim() : "прочее");
            return new NlpResult("add_expense", slots);
        }

        // Time query
        if (Pattern.compile("(?:^|\\b)(?:сколько\\s+(?:сейчас\\s+)?времени|который\\s+час|время\\s+сейчас|what\\s+time)\\b", rxf)
                .matcher(norm).find()) {
            return new NlpResult("get_time", Map.of());
        }

        // Reminder / calendar event
        Matcher mRem = Pattern.compile(
                "(?:^|\\b)(?:напомни(?:\\s+мне)?|создай\\s+напоминание|поставь\\s+напоминание|запланируй|добавь\\s+(?:встречу|событие|напоминание))\\b\\s*(.*)$",
                rxf).matcher(norm);
        if (mRem.find()) {
            Map<String, String> slots = new HashMap<>();
            String what = mRem.group(1);
            slots.put("text", what != null ? what.trim() : "");
            return new NlpResult("add_reminder", slots);
        }

        return new NlpResult("fallback", Map.of());
    }

    private static NlpResult inferSmartHome(String norm) {
        String deviceId = detectSmartHomeDevice(norm);
        if (deviceId == null) {
            return null;
        }

        if ("hall_thermostat".equals(deviceId)) {
            String targetTemperature = extractTemperature(norm);
            if (targetTemperature != null && containsAny(norm, SMART_HOME_SET_KEYWORDS)) {
                return new NlpResult("smart_home_action", Map.of(
                        "deviceId", deviceId,
                        "action", "SET_TEMPERATURE",
                        "payload", targetTemperature));
            }
        }

        if (containsAny(norm, SMART_HOME_OFF_KEYWORDS)) {
            return new NlpResult("smart_home_action", Map.of(
                    "deviceId", deviceId,
                    "action", "TURN_OFF"));
        }
        if (containsAny(norm, SMART_HOME_ON_KEYWORDS)) {
            return new NlpResult("smart_home_action", Map.of(
                    "deviceId", deviceId,
                    "action", "TURN_ON"));
        }
        if (containsAny(norm, SMART_HOME_TOGGLE_KEYWORDS)) {
            return new NlpResult("smart_home_action", Map.of(
                    "deviceId", deviceId,
                    "action", "TOGGLE"));
        }
        return null;
    }

    private static String detectSmartHomeDevice(String norm) {
        for (Map.Entry<String, List<String>> entry : SMART_HOME_DEVICE_ALIASES.entrySet()) {
            if (containsAny(norm, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean containsAny(String norm, List<String> candidates) {
        for (String candidate : candidates) {
            if (norm.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String extractTemperature(String norm) {
        Matcher matcher = TEMPERATURE_VALUE.matcher(norm);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(2) == null
                ? matcher.group(1)
                : matcher.group(1) + "." + matcher.group(2);
    }

    private static boolean isSeconds(String unitTok) {
        if (unitTok == null)
            return false;
        String u = unitTok.toLowerCase(Locale.ROOT);
        u = u.replace('ё', 'е');
        return u.startsWith("сек") || u.equals("с");
    }

    private static Integer parseNumber(String token) {
        if (token == null || token.isEmpty())
            return null;
        token = token.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();

        Matcher m = NUM_TOKEN.matcher(token);
        if (m.matches()) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse numeric token '{}': {}", token, e.getMessage());
            }
        }

        Integer v = RUS_NUM.get(token);
        if (v != null)
            return v;

        if (token.contains(" ")) {
            String[] parts = token.split("\\s+");
            int sum = 0;
            for (String p : parts) {
                Integer pv = RUS_NUM.get(p);
                if (pv == null)
                    return null;
                sum += pv;
            }
            return sum > 0 ? sum : null;
        }

        return null;
    }

    private static Map<String, Integer> buildRusNumbers() {
        Map<String, Integer> m = new HashMap<>();
        m.put("ноль", 0);
        m.put("один", 1);
        m.put("одна", 1);
        m.put("раз", 1);
        m.put("два", 2);
        m.put("две", 2);
        m.put("три", 3);
        m.put("четыре", 4);
        m.put("пять", 5);
        m.put("шесть", 6);
        m.put("семь", 7);
        m.put("восемь", 8);
        m.put("девять", 9);
        m.put("десять", 10);
        m.put("одиннадцать", 11);
        m.put("двенадцать", 12);
        m.put("тринадцать", 13);
        m.put("четырнадцать", 14);
        m.put("пятнадцать", 15);
        m.put("шестнадцать", 16);
        m.put("семнадцать", 17);
        m.put("восемнадцать", 18);
        m.put("девятнадцать", 19);
        m.put("двадцать", 20);
        m.put("тридцать", 30);
        m.put("сорок", 40);
        m.put("пятьдесят", 50);
        m.put("шестьдесят", 60);
        m.put("двадцатку", 20);
        m.put("двадцатка", 20);
        m.put("тридцатку", 30);
        m.put("тридцатка", 30);
        m.put("одну", 1);
        m.put("четверть", 15);
        return m;
    }

    private static Map<String, List<String>> buildSmartHomeDeviceAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("kitchen_light", List.of(
                "кухонный свет",
                "свет на кухне",
                "kitchen light",
                "kitchen lights"));
        aliases.put("desk_lamp", List.of(
                "настольная лампа",
                "настольную лампу",
                "лампу на столе",
                "desk lamp",
                "desk light"));
        aliases.put("hall_thermostat", List.of(
                "термостат в коридоре",
                "термостат",
                "hall thermostat",
                "hallway thermostat"));
        return aliases;
    }
}
