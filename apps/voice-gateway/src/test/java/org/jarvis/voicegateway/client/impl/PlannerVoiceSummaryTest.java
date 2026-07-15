package org.jarvis.voicegateway.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlannerVoiceSummaryTest {

    @Test
    void russianPluralHandlesAllBuckets() {
        assertEquals("задача", PlannerVoiceSummary.pluralTasksRu(1));
        assertEquals("задачи", PlannerVoiceSummary.pluralTasksRu(2));
        assertEquals("задачи", PlannerVoiceSummary.pluralTasksRu(4));
        assertEquals("задач", PlannerVoiceSummary.pluralTasksRu(5));
        assertEquals("задач", PlannerVoiceSummary.pluralTasksRu(11));
        assertEquals("задач", PlannerVoiceSummary.pluralTasksRu(12));
        assertEquals("задач", PlannerVoiceSummary.pluralTasksRu(14));
        assertEquals("задача", PlannerVoiceSummary.pluralTasksRu(21));
        assertEquals("задачи", PlannerVoiceSummary.pluralTasksRu(22));
        assertEquals("задач", PlannerVoiceSummary.pluralTasksRu(25));
    }

    @Test
    void buildsRussianSummaryWithCountAndFocus() {
        String summary = PlannerVoiceSummary.build(true, 4, "Закрыть отчёт по спринту", null);

        assertEquals("Сэр, сегодня у вас 4 задачи. Главный фокус: Закрыть отчёт по спринту.", summary);
    }

    @Test
    void buildsRussianSummaryForSingleTask() {
        String summary = PlannerVoiceSummary.build(true, 1, "Позвонить в банк", null);

        assertTrue(summary.contains("1 задача"), summary);
        assertTrue(summary.contains("Позвонить в банк"), summary);
    }

    @Test
    void buildsRussianEmptyDaySummaryWhenNoTasks() {
        String summary = PlannerVoiceSummary.build(true, 0, null, null);

        assertTrue(summary.contains("открытых задач нет"), summary);
    }

    @Test
    void fallsBackToMessageWhenNoTitleButTasksExist() {
        String summary = PlannerVoiceSummary.build(true, 3, null, "Главное сейчас, сэр: разобрать почту.");

        assertTrue(summary.contains("3 задачи"), summary);
        assertTrue(summary.contains("разобрать почту"), summary);
    }

    @Test
    void buildsEnglishSummaryWithFocus() {
        String summary = PlannerVoiceSummary.build(false, 2, "Ship the release", null);

        assertEquals("Sir, you have 2 open tasks today. Main focus: Ship the release.", summary);
    }
}
