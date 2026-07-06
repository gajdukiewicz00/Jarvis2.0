package org.jarvis.nlp.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based extractor for relative dates, weekday references, and clock times
 * embedded in free-text slots (e.g. reminder bodies). Mirrors the regex +
 * lookup-table style already used by the intent patterns in this package.
 *
 * <p>Intentionally simple: no calendar math, no timezone handling. It only
 * recognizes common Russian phrasing and normalizes it into small machine-
 * readable tokens ("today", "tomorrow", "MONDAY", "09:00", "morning") that a
 * downstream service can resolve against a real clock/calendar.</p>
 */
final class DateTimeEntityExtractor {

    private static final int RXF = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern DAY_AFTER_TOMORROW = Pattern.compile("(?:^|\\b)послезавтра\\b", RXF);
    private static final Pattern TOMORROW = Pattern.compile("(?:^|\\b)завтра\\b", RXF);
    private static final Pattern TODAY = Pattern.compile("(?:^|\\b)сегодня\\b", RXF);

    // Two explicit numbers after "в" — e.g. normalized "в 15 00" (from raw "в 15:00").
    private static final Pattern CLOCK_HOUR_MINUTE = Pattern.compile(
            "(?:^|\\b)в\\s+([01]?\\d|2[0-3])\\s+([0-5]\\d)\\b", RXF);

    // Single hour, optionally followed by "час(а/ов)" and/or a day-part qualifier.
    private static final Pattern CLOCK_HOUR_ONLY = Pattern.compile(
            "(?:^|\\b)в\\s+([01]?\\d|2[0-3])\\s*(?:час(?:а|ов)?)?\\s*(утра|дня|вечера|ночи)?\\b", RXF);

    private static final Pattern DAY_PART = Pattern.compile("(?:^|\\b)(утром|днем|вечером|ночью)\\b", RXF);

    private static final Map<String, String> WEEKDAY_STEMS = buildWeekdayStems();

    /**
     * Extracts relative-date, weekday, and clock-time hints from normalized text.
     * Returns an empty map when nothing is recognized so callers can merge the
     * result into an existing slot map without null checks.
     */
    static Map<String, String> extract(String normalizedText) {
        Map<String, String> found = new LinkedHashMap<>();
        if (normalizedText == null || normalizedText.isBlank()) {
            return found;
        }

        String date = extractDate(normalizedText);
        if (date != null) {
            found.put("date", date);
        }

        String time = extractTime(normalizedText);
        if (time != null) {
            found.put("time", time);
        } else {
            Matcher dayPart = DAY_PART.matcher(normalizedText);
            if (dayPart.find()) {
                found.put("dayPart", normalizeDayPart(dayPart.group(1)));
            }
        }

        return found;
    }

    private static String extractDate(String text) {
        if (DAY_AFTER_TOMORROW.matcher(text).find()) {
            return "day_after_tomorrow";
        }
        if (TOMORROW.matcher(text).find()) {
            return "tomorrow";
        }
        if (TODAY.matcher(text).find()) {
            return "today";
        }
        for (Map.Entry<String, String> entry : WEEKDAY_STEMS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String extractTime(String text) {
        Matcher hourMinute = CLOCK_HOUR_MINUTE.matcher(text);
        if (hourMinute.find()) {
            return formatClock(hourMinute.group(1), hourMinute.group(2), null);
        }
        Matcher hourOnly = CLOCK_HOUR_ONLY.matcher(text);
        if (hourOnly.find()) {
            return formatClock(hourOnly.group(1), "00", hourOnly.group(2));
        }
        return null;
    }

    private static String formatClock(String hourToken, String minuteToken, String dayPartQualifier) {
        try {
            int hour = Integer.parseInt(hourToken);
            int minute = Integer.parseInt(minuteToken);
            hour = applyDayPartQualifier(hour, dayPartQualifier);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int applyDayPartQualifier(int hour, String qualifier) {
        if (qualifier == null) {
            return hour;
        }
        String q = qualifier.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean isAfternoonOrEvening = "дня".equals(q) || "вечера".equals(q);
        return isAfternoonOrEvening && hour < 12 ? hour + 12 : hour;
    }

    private static String normalizeDayPart(String token) {
        String t = token.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return switch (t) {
            case "утром" -> "morning";
            case "днем" -> "afternoon";
            case "вечером" -> "evening";
            case "ночью" -> "night";
            default -> t;
        };
    }

    private static Map<String, String> buildWeekdayStems() {
        // LinkedHashMap: order matters only for readability here, lookups are contains()-based.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("понедельник", "MONDAY");
        m.put("вторник", "TUESDAY");
        m.put("сред", "WEDNESDAY"); // среда/среду/средам
        m.put("четверг", "THURSDAY");
        m.put("пятниц", "FRIDAY"); // пятница/пятницу
        m.put("суббот", "SATURDAY"); // суббота/субботу
        m.put("воскресень", "SUNDAY"); // воскресенье/воскресенья
        return m;
    }

    /** Known weekday token values, exposed for tests. */
    static List<String> knownWeekdayValues() {
        return List.copyOf(WEEKDAY_STEMS.values());
    }

    private DateTimeEntityExtractor() {}
}
