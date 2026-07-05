package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.AnomalyDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private AnomalyDetectionService anomalyDetectionService;

    @BeforeEach
    void setUp() {
        TrendSeriesService trendSeriesService = new TrendSeriesService(lifeTrackerClient, clock);
        anomalyDetectionService = new AnomalyDetectionService(trendSeriesService);
    }

    @Test
    void detectExpenseAnomaliesFlagsDaySpendingFarAboveAverage() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(20, LocalDate.of(2026, 3, 7)),
                expense(20, LocalDate.of(2026, 3, 8)),
                expense(20, LocalDate.of(2026, 3, 9)),
                expense(20, LocalDate.of(2026, 3, 10)),
                expense(20, LocalDate.of(2026, 3, 11)),
                expense(20, LocalDate.of(2026, 3, 12)),
                expense(300, LocalDate.of(2026, 3, 13))));

        List<AnomalyDTO> anomalies = anomalyDetectionService.detectExpenseAnomalies(7, 2.0);

        assertEquals(1, anomalies.size());
        assertEquals(LocalDate.of(2026, 3, 13), anomalies.get(0).day());
        assertEquals(300.0, anomalies.get(0).value());
        assertEquals("dailyExpenseTotal", anomalies.get(0).metric());
        assertTrue(anomalies.get(0).explanation().contains("2026-03-13"));
    }

    @Test
    void detectExpenseAnomaliesReturnsEmptyWhenSeriesTooShort() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(20, LocalDate.of(2026, 3, 12)),
                expense(30, LocalDate.of(2026, 3, 13))));

        assertEquals(List.of(), anomalyDetectionService.detectExpenseAnomalies(7, 2.0));
    }

    @Test
    void detectExpenseAnomaliesReturnsEmptyWhenAllValuesAreEqual() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(20, LocalDate.of(2026, 3, 11)),
                expense(20, LocalDate.of(2026, 3, 12)),
                expense(20, LocalDate.of(2026, 3, 13))));

        assertEquals(List.of(), anomalyDetectionService.detectExpenseAnomalies(7, 2.0));
    }

    private ExpenseDTO expense(double amount, LocalDate day) {
        ExpenseDTO dto = new ExpenseDTO();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setType("EXPENSE");
        dto.setOccurredAt(day.atTime(10, 0));
        return dto;
    }
}
