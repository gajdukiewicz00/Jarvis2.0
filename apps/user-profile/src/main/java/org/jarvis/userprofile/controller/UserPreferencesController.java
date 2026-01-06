package org.jarvis.userprofile.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.service.UserPreferencesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for user preferences
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/profile/preferences")
@RequiredArgsConstructor
public class UserPreferencesController {
    
    private final UserPreferencesService preferencesService;
    
    /**
     * Get user preferences by userId
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserPreferencesDto> getPreferences(@PathVariable String userId) {
        log.info("GET preferences for user: {}", userId);
        
        return preferencesService.getPreferences(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Auto-create default preferences if not found
                    log.info("Preferences not found, creating defaults for: {}", userId);
                    UserPreferencesDto defaults = preferencesService.createDefaultPreferences(userId);
                    return ResponseEntity.ok(defaults);
                });
    }
    
    /**
     * Create or update user preferences
     */
    @PostMapping
    public ResponseEntity<UserPreferencesDto> createOrUpdatePreferences(
            @RequestBody UserPreferencesDto dto
    ) {
        log.info("POST preferences for user: {}", dto.getUserId());
        
        if (dto.getUserId() == null || dto.getUserId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        
        UserPreferencesDto saved = preferencesService.createOrUpdatePreferences(dto);
        return ResponseEntity.ok(saved);
    }
    
    /**
     * Update existing preferences (PUT)
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserPreferencesDto> updatePreferences(
            @PathVariable String userId,
            @RequestBody UserPreferencesDto dto
    ) {
        log.info("PUT preferences for user: {}", userId);
        
        dto.setUserId(userId);
        UserPreferencesDto updated = preferencesService.createOrUpdatePreferences(dto);
        return ResponseEntity.ok(updated);
    }
    
    /**
     * Delete user preferences
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deletePreferences(@PathVariable String userId) {
        log.info("DELETE preferences for user: {}", userId);
        preferencesService.deletePreferences(userId);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
