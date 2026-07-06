package org.jarvis.nlp.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeEntityExtractorTest {

    @Test
    void extractReturnsEmptyMapForNullText() {
        assertTrue(DateTimeEntityExtractor.extract(null).isEmpty());
    }

    @Test
    void extractReturnsEmptyMapForBlankText() {
        assertTrue(DateTimeEntityExtractor.extract("   ").isEmpty());
    }

    @Test
    void extractReturnsEmptyMapWhenNothingRecognized() {
        assertTrue(DateTimeEntityExtractor.extract("позвонить маме").isEmpty());
    }

    @Test
    void extractRecognizesToday() {
        Map<String, String> result = DateTimeEntityExtractor.extract("сегодня позвонить маме");
        assertEquals("today", result.get("date"));
    }

    @Test
    void extractRecognizesTomorrow() {
        Map<String, String> result = DateTimeEntityExtractor.extract("завтра позвонить маме");
        assertEquals("tomorrow", result.get("date"));
    }

    @Test
    void extractRecognizesDayAfterTomorrow() {
        Map<String, String> result = DateTimeEntityExtractor.extract("послезавтра позвонить маме");
        assertEquals("day_after_tomorrow", result.get("date"));
    }

    @Test
    void extractRecognizesWeekday() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни в среду позвонить маме");
        assertEquals("WEDNESDAY", result.get("date"));
    }

    @Test
    void extractRecognizesExplicitHourAndMinute() {
        // "15:00" is stripped to "15 00" by TextNormalizer before reaching the extractor.
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни в 15 00 позвонить");
        assertEquals("15:00", result.get("time"));
    }

    @Test
    void extractAppliesAfternoonQualifierToHour() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни в 3 дня позвонить");
        assertEquals("15:00", result.get("time"));
    }

    @Test
    void extractLeavesMorningHourUnchanged() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни в 9 утра позвонить");
        assertEquals("09:00", result.get("time"));
    }

    @Test
    void extractFallsBackToDayPartWhenNoExplicitClockTime() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни вечером позвонить");
        assertEquals("evening", result.get("dayPart"));
        assertFalse(result.containsKey("time"));
    }

    @Test
    void extractCombinesDateAndTimeWhenBothPresent() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни завтра в 9 утра позвонить");
        assertEquals("tomorrow", result.get("date"));
        assertEquals("09:00", result.get("time"));
    }

    @Test
    void extractPrefersExplicitClockTimeOverDayPart() {
        Map<String, String> result = DateTimeEntityExtractor.extract("напомни вечером в 20 00 позвонить");
        assertEquals("20:00", result.get("time"));
        assertFalse(result.containsKey("dayPart"));
    }
}
