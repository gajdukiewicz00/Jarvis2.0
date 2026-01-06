package org.jarvis.nlp.service.impl;

import java.util.*;
import java.util.regex.*;

final class TextNormalizer {

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
            t = t.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }

        // унифицировать единицы
        t = t.replaceAll("\\bсекунд(?:а|ы)?\\b", "sec");
        t = t.replaceAll("\\bсек\\b", "sec");
        t = t.replaceAll("\\bминут(?:а|ы)?\\b", "min");
        t = t.replaceAll("\\bмин\\b", "min");

        // убрать мусорные слова
        for (String f : FILLERS) {
            t = t.replaceAll("\\b" + Pattern.quote(f) + "\\b", " ");
        }

        return t.replaceAll("\\s+", " ").trim();
    }

    private TextNormalizer() {}
}
