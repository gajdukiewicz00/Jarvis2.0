package org.jarvis.nlp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.EnhancedNlpService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced rule-based NLP service with confidence scoring.
 */
@Service
@Slf4j
public class EnhancedRuleBasedNlpService implements EnhancedNlpService {

    private static final int RXF = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // Patterns
    private static final Pattern HELLO = Pattern.compile(
            "(?:^|\\b)(?:привет|здравствуй|здорово|добрый\\s+(?:день|вечер|утро))\\b", RXF);
    private static final Pattern TIMER_FULL = Pattern.compile(
            "(?:^|\\b)(?:(поставь|установи|заведи|постави)\\s+)?таймер(?:\\s+на)?\\s+([\\p{L}\\d]+)\\s*(секунд(?:у|ы)?|сек|с|минут(?:у|ы)?|мин)\\b",
            RXF);
    private static final Pattern TIMER_SHORT = Pattern.compile(
            "(?:^|\\b)таймер\\s+([\\p{L}\\d]+)(?:\\b|$)", RXF);
    // Require an explicit direction word — a bare "сделай" must NOT match either
    // (otherwise "сделай тише" wrongly hits VOL_UP, which is evaluated first).
    private static final Pattern VOL_UP = Pattern.compile(
            "(?:прибавь|увеличь|подними|погромче|громче)\\s*(?:громкость|звук)?(?:\\s+на\\s+([\\p{L}\\d]+))?", RXF);
    private static final Pattern VOL_DOWN = Pattern.compile(
            "(?:уменьши|убавь|снизь|понизь|потише|тише)\\s*(?:громкость|звук)?(?:\\s+на\\s+([\\p{L}\\d]+))?",
            RXF);
    private static final Pattern VOL_ON = Pattern.compile(
            "(?:^|\\b)(?:громкость|звук)\\s+на\\s+([\\p{L}\\d]+)(?:\\b|$)", RXF);
    private static final Pattern TIME_QUERY = Pattern.compile(
            "(?:^|\\b)(?:сколько\\s+(?:сейчас\\s+)?времени|который\\s+час|время\\s+сейчас|what\\s+time)\\b", RXF);
    private static final Pattern EXPENSE = Pattern.compile(
            "(?:^|\\b)(?:потратил[аи]?|истратил[аи]?|купил[аи]?|расход)\\b.*?(\\d+)\\s*(?:руб|р|₽|евро|eur|€|долл|usd|\\$)?(?:\\s+(?:на|в|за)\\s+([\\p{L}][\\p{L}\\s]*?))?(?:\\b|$)",
            RXF);
    private static final Pattern REMINDER = Pattern.compile(
            "(?:^|\\b)(?:напомни(?:\\s+мне)?|создай\\s+напоминание|поставь\\s+напоминание|запланируй|добавь\\s+(?:встречу|событие|напоминание))\\b\\s*(.*)$",
            RXF);
    private static final Pattern NUM_TOKEN = Pattern.compile("\\d+");

    private static final Map<String, Integer> RUS_NUM = buildRusNumbers();

    @Override
    public EnhancedNlpResult analyzeWithConfidence(String text, String languageCode) {
        if (text == null)
            text = "";
        String norm = TextNormalizer.normalize(text);

        // High confidence patterns
        if (HELLO.matcher(norm).find()) {
            return new EnhancedNlpResult("hello", Map.of(), 0.95, false, null, text);
        }

        // Timer patterns - high confidence if explicit
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
                return new EnhancedNlpResult("set_timer", slots, 0.9, false, null, text);
            }
        }

        // Timer short form - medium confidence
        mt = TIMER_SHORT.matcher(norm);
        if (mt.find()) {
            Integer amount = parseNumber(mt.group(1));
            if (amount != null && amount > 0) {
                Map<String, String> slots = new HashMap<>();
                slots.put("amount", String.valueOf(amount));
                slots.put("unit", "min");
                return new EnhancedNlpResult("set_timer", slots, 0.7, false, null, text);
            }
        }

        // Volume patterns - high confidence
        Matcher mu = VOL_UP.matcher(norm);
        if (mu.find()) {
            Integer delta = parseNumber(mu.group(1));
            if (delta == null || delta <= 0)
                delta = 10;
            Map<String, String> slots = new HashMap<>();
            slots.put("deltaPercent", String.valueOf(delta));
            slots.put("direction", "+");
            return new EnhancedNlpResult("change_volume", slots, 0.85, false, null, text);
        }

        Matcher md = VOL_DOWN.matcher(norm);
        if (md.find()) {
            Integer delta = parseNumber(md.group(1));
            if (delta == null || delta <= 0)
                delta = 10;
            Map<String, String> slots = new HashMap<>();
            slots.put("deltaPercent", String.valueOf(delta));
            slots.put("direction", "-");
            return new EnhancedNlpResult("change_volume", slots, 0.85, false, null, text);
        }

        Matcher mon = VOL_ON.matcher(norm);
        if (mon.find()) {
            Integer delta = parseNumber(mon.group(1));
            if (delta != null && delta > 0) {
                Map<String, String> slots = new HashMap<>();
                slots.put("deltaPercent", String.valueOf(delta));
                slots.put("direction", "+");
                return new EnhancedNlpResult("change_volume", slots, 0.8, false, null, text);
            }
        }

        // Time query
        if (TIME_QUERY.matcher(norm).find()) {
            return new EnhancedNlpResult("get_time", Map.of(), 0.9, false, null, text);
        }

        // Expense logging
        Matcher me = EXPENSE.matcher(norm);
        if (me.find()) {
            Map<String, String> slots = new HashMap<>();
            Integer amount = parseNumber(me.group(1));
            if (amount != null) {
                slots.put("amount", String.valueOf(amount));
            }
            String category = me.group(2);
            slots.put("category", category != null && !category.isBlank() ? category.trim() : "прочее");
            return new EnhancedNlpResult("add_expense", slots, 0.8, false, null, text);
        }

        // Reminder / calendar event
        Matcher mr = REMINDER.matcher(norm);
        if (mr.find()) {
            Map<String, String> slots = new HashMap<>();
            String what = mr.group(1);
            slots.put("text", what != null ? what.trim() : "");
            return new EnhancedNlpResult("add_reminder", slots, 0.75, false, null, text);
        }

        // Fallback - low confidence, needs clarification
        return new EnhancedNlpResult(
                "fallback",
                Map.of(),
                0.2,
                true,
                "Я не уверен, что вы имеете в виду. Можете переформулировать?",
                text);
    }

    @Override
    @Deprecated
    public NlpResult infer(String text, String languageCode) {
        EnhancedNlpResult enhanced = analyzeWithConfidence(text, languageCode);
        return new NlpResult(enhanced.intent(), enhanced.entities());
    }

    private static boolean isSeconds(String unitTok) {
        if (unitTok == null)
            return false;
        String u = unitTok.toLowerCase(Locale.ROOT).replace('ё', 'е');
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
}
