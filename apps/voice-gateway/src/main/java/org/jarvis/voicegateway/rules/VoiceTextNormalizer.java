package org.jarvis.voicegateway.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fuzzy STT-variant normalization applied to the recognized text (only) BEFORE rule
 * matching. Vosk/Whisper mangle app names and command phrases ("вес скотт" for "vs code",
 * "телеграмм" for "телеграм", "с кровь все окна" for "сверни все окна"); this canonicalizes
 * those into the phrase a rule actually matches, so known commands route to their tool
 * instead of falling through to generic LLM chat.
 *
 * <p>Applied to the already-{@link RuleBasedVoiceCommandService#normalize normalized} text
 * (lowercase, ё→е, punctuation stripped, spaces collapsed) — never to configured matcher
 * values.
 */
final class VoiceTextNormalizer {

    private VoiceTextNormalizer() {}

    /**
     * Ordered substring aliases (longest key first, applied in order) mapping common STT
     * mistakes to the canonical phrase a rule matches. Keys/values must already be in
     * normalized form (lowercase, no punctuation).
     */
    private static final Map<String, String> ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        // --- VS Code (STT rarely gets "vs code" right) ---
        aliases.put("визуал студио код", "vs code");
        aliases.put("вижуал студио код", "vs code");
        aliases.put("вес скотт", "vs code");
        aliases.put("вэс скотт", "vs code");
        aliases.put("вес скот", "vs code");
        aliases.put("вес кот", "vs code");
        aliases.put("вэс кот", "vs code");
        aliases.put("вес код", "vs code");
        aliases.put("вэс код", "vs code");
        aliases.put("виз код", "vs code");
        aliases.put("вис код", "vs code");
        aliases.put("вс код", "vs code");
        aliases.put("вskod", "vs code");
        // --- Telegram doubled-letter STT ---
        aliases.put("телеграмму", "телеграм");
        aliases.put("телеграмма", "телеграм");
        aliases.put("телеграмм", "телеграм");
        aliases.put("телегу", "телеграм");
        // --- minimize all windows ("сверни" mangled) ---
        aliases.put("с кровь все окна", "сверни все окна");
        aliases.put("скорой все окна", "сверни все окна");
        aliases.put("с крой все окна", "сверни все окна");
        aliases.put("скрой все окна", "сверни все окна");
        aliases.put("свернуть все окна", "сверни все окна");
        aliases.put("убери все окна", "сверни все окна");
        // --- file manager ---
        aliases.put("файловый менеджер", "файл менеджер");
        return aliases;
    }

    /**
     * Russian cardinal number words → digits, so spoken volume levels ("громкость на сто")
     * become "громкость на 100" and match the numeric SET_VOLUME rule instead of falling to
     * LLM chat. Whole-word replacement only.
     */
    private static final Map<String, String> NUMBER_WORDS = buildNumberWords();

    private static Map<String, String> buildNumberWords() {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("ноль", "0");
        n.put("десять", "10");
        n.put("двадцать", "20");
        n.put("тридцать", "30");
        n.put("сорок", "40");
        n.put("пятьдесят", "50");
        n.put("шестьдесят", "60");
        n.put("семьдесят", "70");
        n.put("восемьдесят", "80");
        n.put("девяносто", "90");
        n.put("сто", "100");
        return n;
    }

    /**
     * Whole-word STT variants that must NOT be applied as substrings (e.g. "юту" is a substring
     * of the correct "ютуб", so a substring replace would corrupt it). Space-bounded only.
     */
    private static final Map<String, String> WORD_ALIASES = buildWordAliases();

    private static Map<String, String> buildWordAliases() {
        Map<String, String> w = new LinkedHashMap<>();
        // YouTube spellings STT gets wrong → the canonical "ютуб" that rule_open_youtube /
        // the YouTube-search rule match.
        w.put("ютюб", "ютуб");
        w.put("ютьюб", "ютуб");
        w.put("юту", "ютуб");
        w.put("ю туб", "ютуб");
        w.put("ю тюб", "ютуб");
        return w;
    }

    /**
     * Applies the alias substitutions to already-normalized text. Longest aliases first so a
     * more specific phrase wins over a shorter overlapping one, then converts number/whole-word.
     */
    static String applyAliases(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        String result = normalized;
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (result.contains(alias.getKey())) {
                result = result.replace(alias.getKey(), alias.getValue());
            }
        }
        result = normalizeWordAliases(result);
        result = normalizeNumberWords(result);
        // Collapse any spaces introduced/left by replacement.
        return result.replaceAll("\\s+", " ").trim();
    }

    /** Replaces whole-word aliases (space-bounded, no partials). */
    private static String normalizeWordAliases(String text) {
        String result = " " + text + " ";
        for (Map.Entry<String, String> alias : WORD_ALIASES.entrySet()) {
            result = result.replace(" " + alias.getKey() + " ", " " + alias.getValue() + " ");
        }
        return result.trim();
    }

    /** Replaces whole-word Russian number words with digits (space-bounded, no partials). */
    private static String normalizeNumberWords(String text) {
        String result = " " + text + " ";
        for (Map.Entry<String, String> word : NUMBER_WORDS.entrySet()) {
            result = result.replace(" " + word.getKey() + " ", " " + word.getValue() + " ");
        }
        return result.trim();
    }
}
