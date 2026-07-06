package org.jarvis.nlp.service.impl;

import java.util.*;
import java.util.regex.*;

final class TextNormalizer {

    // Matches EnhancedRuleBasedNlpService's own flag combo so \b is Unicode-aware
    // (default Java \b only recognizes [a-zA-Z0-9_] as word chars and never
    // matches boundaries around Cyrillic tokens).
    private static final int RXF = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Map<String, String> NUM = Map.ofEntries(
            Map.entry("ноль","0"), Map.entry("одну","1"), Map.entry("одна","1"), Map.entry("один","1"),
            Map.entry("две","2"), Map.entry("два","2"), Map.entry("три","3"), Map.entry("четыре","4"),
            Map.entry("пять","5"), Map.entry("шесть","6"), Map.entry("семь","7"), Map.entry("восемь","8"),
            Map.entry("девять","9"), Map.entry("десять","10"), Map.entry("пятнадцать","15"),
            Map.entry("двадцать","20"), Map.entry("тридцать","30"), Map.entry("сорок","40")
    );

    private static final Set<String> FILLERS = Set.of(
            "пожалуйста","плиз","а","ну","слушай","смотри","ладно","короче","блин","э","эм"
    );

    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-zа-я0-9%\\s]", " ").replaceAll("\\s+", " ").trim();

        // заменить слова-числа на цифры
        for (var e : NUM.entrySet()) {
            t = replaceWordBoundary(t, "\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }

        // унифицировать единицы
        //
        // NOTE: intentionally left on ASCII-only String.replaceAll (not
        // replaceWordBoundary) — RuleBasedNlpService/EnhancedRuleBasedNlpService's
        // own TIMER_FULL/TIMER_SHORT regexes match the *raw* Cyrillic unit literals
        // (секунд/сек/минут/мин) directly against this normalized text. Fixing the
        // \b bug here too would rewrite those tokens to "sec"/"min" before the
        // timer patterns ever see them, breaking timer unit parsing there.
        t = t.replaceAll("\\bсекунд(?:а|ы)?\\b", "sec");
        t = t.replaceAll("\\bсек\\b", "sec");
        t = t.replaceAll("\\bминут(?:а|ы)?\\b", "min");
        t = t.replaceAll("\\bмин\\b", "min");

        // убрать мусорные слова
        for (String f : FILLERS) {
            t = replaceWordBoundary(t, "\\b" + Pattern.quote(f) + "\\b", " ");
        }

        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * Applies a {@code \b}-anchored replacement with {@link Pattern#UNICODE_CHARACTER_CLASS}
     * so word boundaries are recognized around Cyrillic tokens (String.replaceAll's implicit
     * pattern compilation is ASCII-only and never matches boundaries there).
     */
    private static String replaceWordBoundary(String text, String pattern, String replacement) {
        return Pattern.compile(pattern, RXF).matcher(text).replaceAll(replacement);
    }

    private TextNormalizer() {}
}
