package org.jarvis.analytics.service;

import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyReportExportServiceTest {

    @Mock
    private MonthlyReportService monthlyReportService;

    private MonthlyReportExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new MonthlyReportExportService(monthlyReportService);
    }

    private Map<String, Object> sampleReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("month", "2026-03");
        report.put("spentSoFar", new BigDecimal("700"));
        report.put("topCategories", List.of(
                new ExpenseSummaryDTO("All", "Rent", new BigDecimal("500"), "EUR", 1),
                new ExpenseSummaryDTO("All", "Food, drinks", new BigDecimal("200"), "EUR", 5)));
        report.put("sleep", new SleepSummaryDTO(7.5, 25, 30, 187.5));
        report.put("overtime", new OvertimeSummaryDTO(10, 160.0, 35, 30));
        report.put("consistency", new DayScoreDTO(75, "B", Map.<String, Object>of("activeDays", 20)));
        report.put("report", "Отчёт за 2026-03. Потрачено 700.");
        return report;
    }

    @Test
    void exportJsonReturnsTheMonthlyReportAsIs() {
        Map<String, Object> report = sampleReport();
        when(monthlyReportService.monthlyReport()).thenReturn(report);

        assertThat(exportService.exportJson()).isSameAs(report);
    }

    @Test
    void exportCsvContainsSummaryFieldsAndCategoryTable() {
        when(monthlyReportService.monthlyReport()).thenReturn(sampleReport());

        String csv = exportService.exportCsv();

        assertThat(csv).startsWith("field,value\r\n");
        assertThat(csv).contains("month,2026-03\r\n");
        assertThat(csv).contains("spentSoFar,700\r\n");
        assertThat(csv).contains("sleepAverageHours,7.5\r\n");
        assertThat(csv).contains("overtimeHours,10\r\n");
        assertThat(csv).contains("consistencyScore,75\r\n");
        assertThat(csv).contains("consistencyGrade,B\r\n");
        assertThat(csv).contains("category,totalAmount,currency,count\r\n");
        assertThat(csv).contains("Rent,500,EUR,1\r\n");
    }

    @Test
    void exportCsvQuotesCategoryValuesContainingCommas() {
        when(monthlyReportService.monthlyReport()).thenReturn(sampleReport());

        String csv = exportService.exportCsv();

        assertThat(csv).contains("\"Food, drinks\",200,EUR,5\r\n");
    }

    @Test
    void exportCsvHandlesMissingOptionalSectionsGracefully() {
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("month", "2026-03");
        minimal.put("spentSoFar", BigDecimal.ZERO);
        when(monthlyReportService.monthlyReport()).thenReturn(minimal);

        String csv = exportService.exportCsv();

        assertThat(csv).contains("month,2026-03\r\n");
        assertThat(csv).contains("category,totalAmount,currency,count\r\n");
    }
}
