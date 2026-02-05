package org.jarvis.userprofile.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.service.UserPreferencesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        String authUser = requireUserId();
        if (!authUser.equals(userId)) {
            log.warn("Ignoring path userId {} (using authenticated user {})", userId, authUser);
        }
        String effectiveUserId = authUser;
        log.info("GET preferences for user: {}", effectiveUserId);
        
        return preferencesService.getPreferences(effectiveUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Auto-create default preferences if not found
                    log.info("Preferences not found, creating defaults for: {}", effectiveUserId);
                    UserPreferencesDto defaults = preferencesService.createDefaultPreferences(effectiveUserId);
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
        String authUser = requireUserId();
        dto.setUserId(authUser);
        log.info("POST preferences for user: {}", authUser);
        
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
        String authUser = requireUserId();
        if (!authUser.equals(userId)) {
            log.warn("Ignoring path userId {} (using authenticated user {})", userId, authUser);
        }
        log.info("PUT preferences for user: {}", authUser);

        dto.setUserId(authUser);
        UserPreferencesDto updated = preferencesService.createOrUpdatePreferences(dto);
        return ResponseEntity.ok(updated);
    }
    
    /**
     * Delete user preferences
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deletePreferences(@PathVariable String userId) {
        String authUser = requireUserId();
        if (!authUser.equals(userId)) {
            log.warn("Ignoring path userId {} (using authenticated user {})", userId, authUser);
        }
        log.info("DELETE preferences for user: {}", authUser);
        preferencesService.deletePreferences(authUser);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    private String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        return authentication.getName();
    }
}
