package org.jarvis.analytics.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.InsightDTO;
import org.jarvis.analytics.service.InsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Insight / day-score / report layer on top of the raw analytics aggregates. */
@RestController
@RequestMapping("/api/v1/analytics/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;

    @GetMapping
    public List<InsightDTO> insights() {
        return insightService.autoInsights();
    }

    @GetMapping("/day-score")
    public DayScoreDTO dayScore() {
        return insightService.dayScore();
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        return insightService.dailyReport();
    }

    @GetMapping("/forecast")
    public Map<String, Object> forecast() {
        return insightService.budgetForecast();
    }
}
