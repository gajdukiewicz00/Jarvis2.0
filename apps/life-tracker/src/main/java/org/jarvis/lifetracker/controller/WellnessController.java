package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.HabitStreakDTO;
import org.jarvis.lifetracker.dto.WellnessSummaryDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.jarvis.lifetracker.service.WellnessService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Habit / weight / mood / steps / workout / note tracking. */
@RestController
@RequestMapping("/api/v1/life/wellness")
@RequiredArgsConstructor
public class WellnessController {

    private static final int DEFAULT_SUMMARY_DAYS = 30;

    private final WellnessLogRepository repository;
    private final WellnessService wellnessService;

    public record LogRequest(WellnessType type, Double value, String note, LocalDate day) {
    }

    @PostMapping("/log")
    public WellnessLog log(@RequestBody LogRequest request, HttpServletRequest http) {
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        WellnessLog entry = new WellnessLog();
        entry.setUserId(requireUserId(http));
        entry.setType(request.type());
        entry.setNumericValue(request.value());
        entry.setTextValue(request.note());
        entry.setLoggedAt(Instant.now());
        entry.setDay(request.day() != null ? request.day() : LocalDate.now());
        return repository.save(entry);
    }

    @GetMapping("/day")
    public List<WellnessLog> day(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest http) {
        return repository.findByUserIdAndDayOrderByLoggedAtAsc(
                requireUserId(http), date != null ? date : LocalDate.now());
    }

    @GetMapping("/trend")
    public List<WellnessLog> trend(@RequestParam WellnessType type, HttpServletRequest http) {
        return repository.findByUserIdAndTypeOrderByLoggedAtAsc(requireUserId(http), type);
    }

    @GetMapping("/recent")
    public List<WellnessLog> recent(HttpServletRequest http) {
        return repository.findTop200ByUserIdOrderByLoggedAtDesc(requireUserId(http));
    }

    /** Current + longest streak for a single habit, matched by name (case-insensitive). */
    @GetMapping("/habit/streak")
    public HabitStreakDTO habitStreak(@RequestParam(required = false) String name, HttpServletRequest http) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return wellnessService.habitStreak(requireUserId(http), name);
    }

    /** Streaks for every distinct habit the user has logged. */
    @GetMapping("/habits")
    public List<HabitStreakDTO> habits(HttpServletRequest http) {
        return wellnessService.listHabitStreaks(requireUserId(http));
    }

    /**
     * Aggregate stats (average/min/max/latest) for a numeric metric over a date window.
     * Defaults to the trailing {@value #DEFAULT_SUMMARY_DAYS} days ending today when no
     * explicit range is given.
     */
    @GetMapping("/summary")
    public WellnessSummaryDTO summary(
            @RequestParam WellnessType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer days,
            HttpServletRequest http) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        int window = days != null ? days : DEFAULT_SUMMARY_DAYS;
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(Math.max(0, window - 1));
        return wellnessService.summary(requireUserId(http), type, effectiveFrom, effectiveTo);
    }

    /** Ingest a phone Health Connect snapshot (sync-service forwards HEALTH_ENTRY here). */
    public record HealthEntryRequest(Double sleepHours, Long steps, String date) {
    }

    @PostMapping("/health-entry")
    @Transactional
    public Map<String, Object> healthEntry(@RequestBody HealthEntryRequest request, HttpServletRequest http) {
        String userId = requireUserId(http);
        LocalDate day;
        try {
            day = request.date() == null ? LocalDate.now()
                    : LocalDate.parse(request.date().substring(0, Math.min(10, request.date().length())));
        } catch (RuntimeException e) {
            day = LocalDate.now();
        }
        Instant now = Instant.now();
        int saved = 0;
        if (request.sleepHours() != null) {
            saveEntry(userId, WellnessType.SLEEP, request.sleepHours(), day, now);
            saved++;
        }
        if (request.steps() != null) {
            saveEntry(userId, WellnessType.STEPS, request.steps().doubleValue(), day, now);
            saved++;
        }
        return Map.of("saved", saved, "day", day.toString());
    }

    private void saveEntry(String userId, WellnessType type, Double value, LocalDate day, Instant when) {
        WellnessLog entry = new WellnessLog();
        entry.setUserId(userId);
        entry.setType(type);
        entry.setNumericValue(value);
        entry.setLoggedAt(when);
        entry.setDay(day);
        repository.save(entry);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
