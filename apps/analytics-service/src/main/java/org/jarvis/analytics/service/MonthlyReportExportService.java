package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * CSV/JSON export of {@link MonthlyReportService#monthlyReport()} for the
 * monthly-report export endpoint. JSON export reuses the existing
 * Jackson-serializable map as-is; CSV export flattens it into a small
 * two-section CSV (scalar summary fields, then a top-expense-categories
 * table) since the source report mixes scalars, nested DTOs, and a list.
 */
@Service
@RequiredArgsConstructor
public class MonthlyReportExportService {

    private static final String CSV_LINE_END = "\r\n";

    private final MonthlyReportService monthlyReportService;

    /** JSON export: the monthly report map as-is (Jackson-serializable by the controller). */
    public Map<String, Object> exportJson() {
        return monthlyReportService.monthlyReport();
    }

    /** CSV export: a "field,value" summary section followed by a top-categories table. */
    public String exportCsv() {
        Map<String, Object> report = monthlyReportService.monthlyReport();
        StringBuilder csv = new StringBuilder();

        csv.append("field,value").append(CSV_LINE_END);
        appendRow(csv, "month", report.get("month"));
        appendRow(csv, "spentSoFar", report.get("spentSoFar"));
        appendSleepRows(csv, report.get("sleep"));
        appendOvertimeRows(csv, report.get("overtime"));
        appendConsistencyRows(csv, report.get("consistency"));
        appendRow(csv, "report", report.get("report"));

        csv.append(CSV_LINE_END).append("category,totalAmount,currency,count").append(CSV_LINE_END);
        appendCategoryRows(csv, report.get("topCategories"));
        return csv.toString();
    }

    private void appendCategoryRows(StringBuilder csv, Object topCategoriesObj) {
        if (!(topCategoriesObj instanceof List<?> topCategories)) {
            return;
        }
        for (Object entry : topCategories) {
            if (entry instanceof ExpenseSummaryDTO category) {
                csv.append(escape(category.getCategory())).append(',')
                        .append(escape(category.getTotalAmount())).append(',')
                        .append(escape(category.getCurrency())).append(',')
                        .append(category.getCount()).append(CSV_LINE_END);
            }
        }
    }

    private void appendSleepRows(StringBuilder csv, Object sleepObj) {
        if (sleepObj instanceof SleepSummaryDTO sleep) {
            appendRow(csv, "sleepAverageHours", sleep.getAverageHours());
            appendRow(csv, "sleepDaysSampled", sleep.getDaysSampled());
        }
    }

    private void appendOvertimeRows(StringBuilder csv, Object overtimeObj) {
        if (overtimeObj instanceof OvertimeSummaryDTO overtime) {
            appendRow(csv, "overtimeHours", overtime.getOvertimeHours());
            appendRow(csv, "trackedWorkHours", overtime.getTrackedWorkHours());
        }
    }

    private void appendConsistencyRows(StringBuilder csv, Object consistencyObj) {
        if (consistencyObj instanceof DayScoreDTO consistency) {
            appendRow(csv, "consistencyScore", consistency.score());
            appendRow(csv, "consistencyGrade", consistency.grade());
        }
    }

    private void appendRow(StringBuilder csv, String field, Object value) {
        csv.append(escape(field)).append(',').append(escape(value)).append(CSV_LINE_END);
    }

    /** Minimal RFC-4180-style CSV escaping: quote fields containing a comma, quote, or newline. */
    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
