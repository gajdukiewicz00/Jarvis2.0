package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.domain.UserHabit;
import org.jarvis.userprofile.domain.UserPriority;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.jarvis.userprofile.service.UserProfileProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-profile")
public class UserProfileController {

    private final UserGoalRepository userGoalRepository;
    private final UserHabitRepository userHabitRepository;
    private final UserPriorityRepository userPriorityRepository;
    private final UserProfileProvisioningService userProfileProvisioningService;

    public UserProfileController(UserGoalRepository userGoalRepository,
                                 UserHabitRepository userHabitRepository,
                                 UserPriorityRepository userPriorityRepository,
                                 UserProfileProvisioningService userProfileProvisioningService) {
        this.userGoalRepository = userGoalRepository;
        this.userHabitRepository = userHabitRepository;
        this.userPriorityRepository = userPriorityRepository;
        this.userProfileProvisioningService = userProfileProvisioningService;
    }

    // Goals
    @GetMapping("/{userId}/goals")
    public List<UserGoal> getUserGoals(@PathVariable String userId) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        if (!authUser.equals(userId)) {
            userId = authUser;
        }
        return userGoalRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/goals")
    public UserGoal createUserGoal(@PathVariable String userId, @RequestBody UserGoal goal) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        goal.setUserId(authUser);
        return userGoalRepository.save(goal);
    }

    // Habits
    @GetMapping("/{userId}/habits")
    public List<UserHabit> getUserHabits(@PathVariable String userId) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        if (!authUser.equals(userId)) {
            userId = authUser;
        }
        return userHabitRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/habits")
    public UserHabit createUserHabit(@PathVariable String userId, @RequestBody UserHabit habit) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        habit.setUserId(authUser);
        return userHabitRepository.save(habit);
    }

    // Priorities
    @GetMapping("/{userId}/priorities")
    public List<UserPriority> getUserPriorities(@PathVariable String userId) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        if (!authUser.equals(userId)) {
            userId = authUser;
        }
        return userPriorityRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/priorities")
    public UserPriority createUserPriority(@PathVariable String userId, @RequestBody UserPriority priority) {
        String authUser = requireUserId();
        userProfileProvisioningService.ensureProfileExists(authUser);
        priority.setUserId(authUser);
        return userPriorityRepository.save(priority);
    }

    private String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
        }
        return authentication.getName();
    }
}
