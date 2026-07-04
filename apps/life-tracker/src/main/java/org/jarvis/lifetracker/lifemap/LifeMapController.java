package org.jarvis.lifetracker.lifemap;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 11 — REST surface for the desktop life-map panel.
 *
 * <ul>
 *   <li>{@code POST /api/v1/life-map/time-entries} — agent posts an
 *       activity record (window/app + duration); classifier picks
 *       category if no hint supplied.</li>
 *   <li>{@code GET  /api/v1/life-map/activity?userId=&date=}</li>
 *   <li>{@code GET  /api/v1/life-map/summary?userId=&date=}</li>
 *   <li>{@code GET  /api/v1/life-map/warnings?userId=&date=}</li>
 *   <li>{@code GET  /api/v1/life-map/recommendations/{warningId}/explanation}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/life-map")
@RequiredArgsConstructor
public class LifeMapController {

    private final InMemoryActivityStore activityStore;
    private final TimeClassifier classifier;
    private final DailySummaryService summaryService;
    private final ProactiveWarningEngine warningEngine;

    @PostMapping("/time-entries")
    public ResponseEntity<LifeMapDtos.ActivityEntry> record(@Valid @RequestBody LifeMapDtos.TimeEntryRequest body) {
        TimeCategory category = body.categoryHint() == null
                ? classifier.classify(body.appName(), body.windowTitle())
                : body.categoryHint();
        LifeMapDtos.ActivityEntry entry = activityStore.record(
                body.userId(), body.appName(), body.windowTitle(),
                body.startedAt(), body.endedAt(),
                body.durationSeconds(), category, "agent");
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/activity")
    public List<LifeMapDtos.ActivityEntry> activity(@RequestParam String userId,
                                                    @RequestParam(required = false) LocalDate date) {
        return activityStore.entriesForDay(userId, date == null ? LocalDate.now() : date);
    }

    @GetMapping("/summary")
    public LifeMapDtos.DailySummary summary(@RequestParam String userId,
                                            @RequestParam(required = false) LocalDate date) {
        return summaryService.summarise(userId, date);
    }

    /** 7-day rollup: per-day summaries plus weekly totals (tracked time, income, expense, avg sleep). */
    @GetMapping("/summary/week")
    public Map<String, Object> weekSummary(@RequestParam String userId,
                                           @RequestParam(required = false) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        List<LifeMapDtos.DailySummary> days = new ArrayList<>();
        long totalSeconds = 0;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        double sleepSum = 0;
        int sleepDays = 0;
        for (int i = 6; i >= 0; i--) {
            LifeMapDtos.DailySummary d = summaryService.summarise(userId, end.minusDays(i));
            days.add(d);
            totalSeconds += d.totalTrackedSeconds();
            if (d.financeIncome() != null) {
                income = income.add(d.financeIncome());
            }
            if (d.financeExpense() != null) {
                expense = expense.add(d.financeExpense());
            }
            if (d.sleepHours() != null) {
                sleepSum += d.sleepHours();
                sleepDays++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", end.minusDays(6).toString());
        out.put("to", end.toString());
        out.put("totalTrackedHours", Math.round(totalSeconds / 360.0) / 10.0);
        out.put("income", income);
        out.put("expense", expense);
        out.put("avgSleepHours", sleepDays > 0 ? Math.round(sleepSum / sleepDays * 10.0) / 10.0 : null);
        out.put("days", days);
        return out;
    }

    @GetMapping("/warnings")
    public List<LifeMapDtos.ProactiveWarning> warnings(@RequestParam String userId,
                                                       @RequestParam(required = false) LocalDate date) {
        return summaryService.summarise(userId, date).warnings();
    }

    @GetMapping("/recommendations/{warningId}/explanation")
    public ResponseEntity<LifeMapDtos.RecommendationExplanation> explanation(@PathVariable String warningId) {
        LifeMapDtos.RecommendationExplanation rec = warningEngine.explanation(warningId);
        return rec == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(rec);
    }
}
