package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal/planner")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalPlannerVoiceNotificationController {

    private final NotificationService notificationService;

    @PostMapping("/voice-notify")
    public ResponseEntity<?> sendVoiceNotification(@RequestBody Map<String, Object> body) {
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;
        String message = body.get("message") != null ? String.valueOf(body.get("message")) : null;

        if (userId == null || userId.isBlank() || message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and message are required"));
        }

        log.info("Internal planner voice notification request for userId={}", userId);
        boolean delivered = notificationService.sendVoiceNotification(userId, message);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "delivered", delivered,
                "status", delivered ? "delivered" : "not_delivered"));
    }
}
