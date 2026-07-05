package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.dto.RollupDTO;
import org.jarvis.lifetracker.service.RollupService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;

/** Weekly / monthly rollups combining finance + wellness data (Roadmap P1 #11). */
@RestController
@RequestMapping("/api/v1/life/rollup")
@RequiredArgsConstructor
public class RollupController {

    private final RollupService rollupService;

    /** Weekly (Mon-Sun) rollup for the week containing {@code date} (defaults to the current week). */
    @GetMapping("/week")
    public RollupDTO week(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest http) {
        return rollupService.weeklyRollup(requireUserId(http), date);
    }

    /** Monthly rollup for {@code month} (format {@code yyyy-MM}, defaults to the current month). */
    @GetMapping("/month")
    public RollupDTO month(@RequestParam(required = false) String month, HttpServletRequest http) {
        YearMonth parsed = month != null ? YearMonth.parse(month) : null;
        return rollupService.monthlyRollup(requireUserId(http), parsed);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
