package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.AutoActionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auto-actions API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/actions")
@RequiredArgsConstructor
public class AutoActionController {
    
    private final AutoActionService autoActionService;
    
    /**
     * Manually trigger focus mode
     */
    @PostMapping("/focus-mode")
    public ResponseEntity<Map<String, String>> triggerFocusMode(
            @RequestParam String userId,
            @RequestParam(defaultValue = "WORK") String mode
    ) {
        log.info("Manually triggering focus mode: {} for user: {}", mode, userId);
        
        autoActionService.triggerFocusMode(userId, mode);
        
        return ResponseEntity.ok(Map.of(
            "status", "activated",
            "mode", mode
        ));
    }
    
    /**
     * Start music playlist
     */
    @PostMapping("/music")
    public ResponseEntity<Map<String, String>> startMusic(
            @RequestParam String userId,
            @RequestParam(defaultValue = "WORK") String playlistType
    ) {
        log.info("Starting music: {} for user: {}", playlistType, userId);
        
        autoActionService.startMusicPlaylist(userId, playlistType);
        
        return ResponseEntity.ok(Map.of(
            "status", "started",
            "playlist", playlistType
        ));
    }
    
    /**
     * Start Pomodoro timer
     */
    @PostMapping("/pomodoro")
    public ResponseEntity<Map<String, Object>> startPomodoro(
            @RequestParam String userId,
            @RequestParam(defaultValue = "25") int duration
    ) {
        log.info("Starting Pomodoro ({} min) for user: {}", duration, userId);
        
        autoActionService.startPomodoroTimer(userId, duration);
        
        return ResponseEntity.ok(Map.of(
            "status", "started",
            "durationMinutes", duration
        ));
    }
    
    /**
     * Suggest break
     */
    @PostMapping("/break")
    public ResponseEntity<Map<String, String>> suggestBreak(@RequestParam String userId) {
        log.info("Suggesting break for user: {}", userId);
        
        autoActionService.suggestBreak(userId);
        
        return ResponseEntity.ok(Map.of(
            "status", "suggested"
        ));
    }
}
