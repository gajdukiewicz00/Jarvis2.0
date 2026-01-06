package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.domain.UserHabit;
import org.jarvis.userprofile.domain.UserPriority;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-profile")
public class UserProfileController {

    private final UserGoalRepository userGoalRepository;
    private final UserHabitRepository userHabitRepository;
    private final UserPriorityRepository userPriorityRepository;

    public UserProfileController(UserGoalRepository userGoalRepository,
                                 UserHabitRepository userHabitRepository,
                                 UserPriorityRepository userPriorityRepository) {
        this.userGoalRepository = userGoalRepository;
        this.userHabitRepository = userHabitRepository;
        this.userPriorityRepository = userPriorityRepository;
    }

    // Goals
    @GetMapping("/{userId}/goals")
    public List<UserGoal> getUserGoals(@PathVariable String userId) {
        return userGoalRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/goals")
    public UserGoal createUserGoal(@PathVariable String userId, @RequestBody UserGoal goal) {
        goal.setUserId(userId);
        return userGoalRepository.save(goal);
    }

    // Habits
    @GetMapping("/{userId}/habits")
    public List<UserHabit> getUserHabits(@PathVariable String userId) {
        return userHabitRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/habits")
    public UserHabit createUserHabit(@PathVariable String userId, @RequestBody UserHabit habit) {
        habit.setUserId(userId);
        return userHabitRepository.save(habit);
    }

    // Priorities
    @GetMapping("/{userId}/priorities")
    public List<UserPriority> getUserPriorities(@PathVariable String userId) {
        return userPriorityRepository.findByUserId(userId);
    }

    @PostMapping("/{userId}/priorities")
    public UserPriority createUserPriority(@PathVariable String userId, @RequestBody UserPriority priority) {
        priority.setUserId(userId);
        return userPriorityRepository.save(priority);
    }
}
