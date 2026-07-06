package org.jarvis.nlp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.IntentCandidate;
import org.jarvis.nlp.model.NlpResult;
import org.jarvis.nlp.service.EnhancedNlpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced rule-based NLP service with confidence scoring.
 *
 * <p>Classification confidence is compared against a configurable threshold
 * ({@code jarvis.nlp.confidence.threshold}). Results below the threshold are
 * downgraded to the {@link #UNKNOWN_INTENT} intent with {@code needsClarification}
 * set and a ranked list of alternative intents attached, so the orchestrator can
 * ask the user instead of guessing.</p>
 */
@Service
@Slf4j
public class EnhancedRuleBasedNlpService implements EnhancedNlpService {

    /**
     * Intent name returned when the top match's confidence is below the
     * configured clarification threshold.
     */
    public static final String UNKNOWN_INTENT = "UNKNOWN";

    private static final String DEFAULT_CLARIFICATION_QUESTION =
            "Я не уверен, что вы имеете в виду. Можете переформулировать?";

    private static final int RXF = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // Patterns
    private static final Pattern HELLO = Pattern.compile(
            "(?:^|\\b)(?:привет|здравствуй|здорово|добрый\\s+(?:день|вечер|утро))\\b", RXF);
    private static final Pattern TIMER_FULL = Pattern.compile(
            "(?:^|\\b)(?:(поставь|установи|заведи|постави)\\s+)?таймер(?:\\s+на)?\\s+([\\p{L}\\d]+)\\s*"
                    + "(секунд(?:у|ы)?|сек|с|минут(?:у|ы)?|мин|час(?:а|ов)?|ч)\\b",
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

    /**
     * Lightweight (intent, pattern, confidence) signal used only to suggest
     * clarification candidates. Kept separate from the primary sequential
     * matcher above so the well-tested first-match classification order is
     * never touched by this additive feature.
     */
    private record IntentSignal(String intent, Pattern pattern, double confidence) {}

    private static final List<IntentSignal> INTENT_SIGNALS = List.of(
            new IntentSignal("hello", HELLO, 0.95),
            new IntentSignal("set_timer", TIMER_FULL, 0.9),
            new IntentSignal("set_timer", TIMER_SHORT, 0.7),
            new IntentSignal("change_volume", VOL_UP, 0.85),
            new IntentSignal("change_volume", VOL_DOWN, 0.85),
            new IntentSignal("change_volume", VOL_ON, 0.8),
            new IntentSignal("get_time", TIME_QUERY, 0.9),
            new IntentSignal("add_expense", EXPENSE, 0.8),
            new IntentSignal("add_reminder", REMINDER, 0.75));

    private final double confidenceThreshold;
    private final int topK;

    public EnhancedRuleBasedNlpService(
            @Value("${jarvis.nlp.confidence.threshold:0.5}") double confidenceThreshold,
            @Value("${jarvis.nlp.confidence.top-k:3}") int topK) {
        this.confidenceThreshold = confidenceThreshold;
        this.topK = topK;
    }

    @Override
    public EnhancedNlpResult analyzeWithConfidence(String text, String languageCode) {
        String safeText = text == null ? "" : text;
        String norm = TextNormalizer.normalize(safeText);

        EnhancedNlpResult matched = classifyPatterns(norm, safeText);
        if (matched.confidence() >= confidenceThreshold) {
            return matched;
        }

        String question = matched.clarificationQuestion() != null
                ? matched.clarificationQuestion()
                : DEFAULT_CLARIFICATION_QUESTION;
        List<IntentCandidate> candidates = suggestCandidates(norm);
        return new EnhancedNlpResult(
                UNKNOWN_INTENT, matched.entities(), matched.confidence(), true, question, safeText, candidates);
    }

    /**
     * Sequential first-match intent classification. Order matters: earlier
     * patterns take priority over later ones when more than one could match.
     */
    private EnhancedNlpResult classifyPatterns(String norm, String text) {
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
                String unit = resolveTimeUnit(unitTok);
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
            slots.putAll(DateTimeEntityExtractor.extract(norm));
            return new EnhancedNlpResult("add_reminder", slots, 0.75, false, null, text);
        }

        // Fallback - low confidence, needs clarification
        return new EnhancedNlpResult(
                "fallback",
                Map.of(),
                0.2,
                true,
                DEFAULT_CLARIFICATION_QUESTION,
                text);
    }

    /**
     * Scans every known pattern (not just the first that matched) so a
     * clarification prompt can offer plausible alternatives. Distinct intents
     * only, ranked by their base confidence, capped at {@link #topK}.
     */
    private List<IntentCandidate> suggestCandidates(String norm) {
        List<IntentCandidate> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IntentSignal signal : INTENT_SIGNALS) {
            if (seen.contains(signal.intent())) {
                continue;
            }
            if (signal.pattern().matcher(norm).find()) {
                found.add(new IntentCandidate(signal.intent(), signal.confidence()));
                seen.add(signal.intent());
            }
        }
        found.sort(Comparator.comparingDouble(IntentCandidate::confidence).reversed());
        return found.size() > topK ? List.copyOf(found.subList(0, topK)) : List.copyOf(found);
    }

    @Override
    @Deprecated
    public NlpResult infer(String text, String languageCode) {
        EnhancedNlpResult enhanced = analyzeWithConfidence(text, languageCode);
        return new NlpResult(enhanced.intent(), enhanced.entities());
    }

    /**
     * Resolves a matched timer unit token to one of "sec", "min", or "hour".
     * Defaults to "min" when the token is missing or unrecognized, matching
     * the prior sec-vs-min-only behavior.
     */
    private static String resolveTimeUnit(String unitTok) {
        if (unitTok == null)
            return "min";
        String u = unitTok.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (u.startsWith("сек") || u.equals("с"))
            return "sec";
        if (u.startsWith("час") || u.equals("ч"))
            return "hour";
        return "min";
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
