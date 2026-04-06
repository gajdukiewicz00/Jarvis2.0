package org.jarvis.userprofile.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.userprofile.dto.PlanningContextDto;
import org.jarvis.userprofile.dto.UserGoalDto;
import org.jarvis.userprofile.dto.UserHabitDto;
import org.jarvis.userprofile.dto.UserPriorityDto;
import org.jarvis.userprofile.service.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping("/{userId}/planning-context")
    public PlanningContextDto getPlanningContext(@PathVariable String userId) {
        return userProfileService.getPlanningContext(resolveUserId(userId));
    }

    // Goals
    @GetMapping("/{userId}/goals")
    public List<UserGoalDto> getUserGoals(@PathVariable String userId) {
        return userProfileService.getGoals(resolveUserId(userId));
    }

    @PostMapping("/{userId}/goals")
    public UserGoalDto createUserGoal(@PathVariable String userId, @RequestBody UserGoalDto goal) {
        return userProfileService.createGoal(resolveUserId(userId), goal);
    }

    // Habits
    @GetMapping("/{userId}/habits")
    public List<UserHabitDto> getUserHabits(@PathVariable String userId) {
        return userProfileService.getHabits(resolveUserId(userId));
    }

    @PostMapping("/{userId}/habits")
    public UserHabitDto createUserHabit(@PathVariable String userId, @RequestBody UserHabitDto habit) {
        return userProfileService.createHabit(resolveUserId(userId), habit);
    }

    // Priorities
    @GetMapping("/{userId}/priorities")
    public List<UserPriorityDto> getUserPriorities(@PathVariable String userId) {
        return userProfileService.getPriorities(resolveUserId(userId));
    }

    @PostMapping("/{userId}/priorities")
    public UserPriorityDto createUserPriority(@PathVariable String userId, @RequestBody UserPriorityDto priority) {
        return userProfileService.createPriority(resolveUserId(userId), priority);
    }

    private String resolveUserId(String pathUserId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        // Service-delegated calls: GatewayAuthFilter sets details to "delegated-by:<service>"
        // when a service JWT with X-User-Id header is used. Trust the path userId in this case.
        if (auth.getDetails() instanceof String details && details.startsWith("delegated-by:")) {
            return pathUserId;
        }
        String authUser = auth.getName();
        return authUser.equals(pathUserId) ? pathUserId : authUser;
    }

    private String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        return authentication.getName();
    }
}
