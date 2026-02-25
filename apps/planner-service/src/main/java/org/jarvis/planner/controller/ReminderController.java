package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.service.ReminderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/planner/reminders")
@RequiredArgsConstructor
public class ReminderController {
    
    private final ReminderService reminderService;
    
    @GetMapping
    public ResponseEntity<List<Reminder>> getReminders(Authentication authentication) {
        String userId = authentication.getName();
        log.info("GET active reminders for user: {}", userId);
        List<Reminder> reminders = reminderService.getActiveReminders(userId);
        return ResponseEntity.ok(reminders);
    }
    
    @GetMapping("/upcoming")
    public ResponseEntity<List<Reminder>> getUpcomingReminders(
            Authentication authentication,
            @RequestParam(required = false, defaultValue = "7") int days
    ) {
        String userId = authentication.getName();
        log.info("GET upcoming reminders for user: {} (next {} days)", userId, days);
        
        Instant start = Instant.now();
        Instant end = start.plus(days, ChronoUnit.DAYS);
        
        List<Reminder> reminders = reminderService.getUpcomingReminders(userId, start, end);
        return ResponseEntity.ok(reminders);
    }
    
    @PostMapping
    public ResponseEntity<Reminder> createReminder(@RequestBody Reminder reminder, Authentication authentication) {
        reminder.setUserId(authentication.getName());
        log.info("POST reminder for user: {}", reminder.getUserId());
        Reminder created = reminderService.createReminder(reminder);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
