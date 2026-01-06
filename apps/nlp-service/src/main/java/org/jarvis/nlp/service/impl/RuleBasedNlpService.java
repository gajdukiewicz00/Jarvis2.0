package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.NlpService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
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
            "(?:сделай(?:-ка)?|прибавь|увеличь|подними|громче)(?:\\s+(?:громкость|звук))?(?:\\s+на\\s+([\\p{L}\\d]+))?",
            RXF);

    private static final Pattern VOL_DOWN = Pattern.compile(
            "(?:сделай(?:-ка)?|уменьши|убавь|снизь|понизь|тише)(?:\\s+(?:громкость|звук))?(?:\\s+на\\s+([\\p{L}\\d]+))?",
            RXF);

    private static final Pattern VOL_SET = Pattern.compile(
            "(?:^|\\b)(?:громкость|звук)\\s+(?:на\\s+)?([\\p{L}\\d]+)(?:\\s*%)?(?:\\b|$)", RXF);

    private static final Pattern MUTE = Pattern.compile(
            "(?:^|\\b)(?:выключи|отключи|убери)\\s+(?:звук|громкость)|замолчи|mute\\b", RXF);

    private static final Pattern UNMUTE = Pattern.compile(
            "(?:^|\\b)(?:включи|верни)\\s+(?:звук|громкость)|unmute\\b", RXF);

    // ============ MEDIA CONTROL ============
    private static final Pattern PLAY = Pattern.compile(
            "(?:^|\\b)(?:играй|воспроизводи|продолжи|play|запусти\\s+музыку)\\b", RXF);

    private static final Pattern PAUSE = Pattern.compile(
            "(?:^|\\b)(?:пауза|стоп|останови|pause|stop|поставь\\s+на\\s+паузу)\\b", RXF);

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

    private static final Pattern NUM_TOKEN = Pattern.compile("\\d+");

    private static final Map<String, Integer> RUS_NUM = buildRusNumbers();

    @Override
    public NlpResult infer(String text, String languageCode) {
        if (text == null)
            text = "";
        String norm = TextNormalizer.normalize(text);

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

        return new NlpResult("fallback", Map.of());
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
            } catch (NumberFormatException ignore) {
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
}
