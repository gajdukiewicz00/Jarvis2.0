package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.websocket.VoiceWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal controller for pushing TTS notifications to active voice websocket sessions.
 */
@Slf4j
@RestController
@RequestMapping("/internal/voice")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalVoiceNotificationController {

    private final VoiceWebSocketHandler voiceWebSocketHandler;

    @PostMapping("/notify")
    public ResponseEntity<?> sendNotification(@RequestBody Map<String, Object> body) {
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;
        String message = body.get("message") != null ? String.valueOf(body.get("message")) : null;
        String languageCode = body.get("languageCode") != null ? String.valueOf(body.get("languageCode")) : null;

        if (userId == null || userId.isBlank() || message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and message are required"));
        }

        int delivered = voiceWebSocketHandler.sendNotificationToUser(userId, message, languageCode);
        if (delivered == 0) {
            log.warn("No active voice sessions for user {}", userId);
            return ResponseEntity.status(404).body(Map.of(
                    "status", "user_not_connected",
                    "userId", userId));
        }

        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "userId", userId,
                "sessions", delivered));
    }
}
