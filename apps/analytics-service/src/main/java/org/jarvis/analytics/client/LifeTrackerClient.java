package org.jarvis.analytics.client;

import org.jarvis.analytics.dto.CalendarEventDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@FeignClient(name = "life-tracker", url = "${jarvis.life-tracker.url:http://life-tracker:8085}")
public interface LifeTrackerClient {

    @GetMapping("/actuator/health/readiness")
    Map<String, Object> getReadiness();

    @GetMapping("/api/v1/life/finance/expenses")
    List<ExpenseDTO> getExpenses();

    @GetMapping("/api/v1/life/time/records")
    List<TimeRecordDTO> getTimeRecords();

    @GetMapping("/api/v1/life/calendar/events")
    List<CalendarEventDTO> getCalendarEvents();
}
