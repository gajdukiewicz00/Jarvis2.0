package org.jarvis.voicegateway.client.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceVoiceSummaryTest {

    @Test
    void buildsRussianSummaryWithMonthTodayAndCategories() {
        Map<String, Object> cats = new LinkedHashMap<>();
        cats.put("продукты", 5000);
        cats.put("транспорт", 2000);
        cats.put("кафе", 1500);

        String summary = FinanceVoiceSummary.build(true, 12000, 800, "RUB", cats);

        assertTrue(summary.contains("сегодня вы потратили 800"), summary);
        assertTrue(summary.contains("за месяц 12000"), summary);
        assertTrue(summary.contains("RUB"), summary);
        assertTrue(summary.contains("продукты"), summary);
    }

    @Test
    void buildsSummaryWithOnlyMonthWhenTodayMissing() {
        String summary = FinanceVoiceSummary.build(true, "3400.50", null, "RUB", null);

        assertTrue(summary.contains("за месяц"), summary);
        assertTrue(summary.contains("3400.5"), summary);
    }

    @Test
    void handlesNoDataGracefully() {
        String summary = FinanceVoiceSummary.build(true, null, null, null, null);

        assertTrue(summary.contains("нет данных"), summary);
    }

    @Test
    void topCategoriesAreOrderedByAmountDescending() {
        Map<String, Object> cats = new LinkedHashMap<>();
        cats.put("кафе", 100);
        cats.put("продукты", 900);
        cats.put("транспорт", 500);

        String summary = FinanceVoiceSummary.build(true, 1500, null, "RUB", cats);

        int prod = summary.indexOf("продукты");
        int transp = summary.indexOf("транспорт");
        int cafe = summary.indexOf("кафе");
        assertTrue(prod >= 0 && transp > prod && cafe > transp, "expected продукты, транспорт, кафе order: " + summary);
    }
}
