package org.jarvis.userprofile.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.domain.UserHabit;
import org.jarvis.userprofile.domain.UserPriority;
import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.dto.PlanningContextDto;
import org.jarvis.userprofile.dto.UserGoalDto;
import org.jarvis.userprofile.dto.UserHabitDto;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.dto.UserPriorityDto;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserGoalRepository userGoalRepository;
    private final UserHabitRepository userHabitRepository;
    private final UserPriorityRepository userPriorityRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileProvisioningService provisioningService;
    private final UserPreferencesService preferencesService;

    @Transactional(readOnly = true)
    public PlanningContextDto getPlanningContext(String userId) {
        UserProfile profile = requireProfile(userId);
        Optional<UserPreferencesDto> preferences = preferencesService.getPreferences(userId);
        List<UserGoalDto> goals = userGoalRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing((UserGoal goal) -> isActive(goal.getStatus()) ? 0 : 1)
                        .thenComparing(UserGoal::getDeadline, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserGoal::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toGoalDto)
                .toList();
        List<UserHabitDto> habits = userHabitRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing((UserHabit habit) -> normalized(habit.getTimeOfDay()))
                        .thenComparing(UserHabit::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toHabitDto)
                .toList();
        List<UserPriorityDto> priorities = userPriorityRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing(UserPriority::getLevel, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserPriority::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toPriorityDto)
                .toList();

        PlanningContextDto context = new PlanningContextDto();
        context.setUserId(userId);
        context.setDisplayName(profile.getDisplayName());
        context.setTimezone(preferences.map(UserPreferencesDto::getTimezone).orElse(profile.getTimezone()));
        context.setLanguage(preferences.map(UserPreferencesDto::getLanguage).orElse(profile.getLanguage()));
        context.setOccupation(preferences.map(UserPreferencesDto::getOccupation).orElse(null));
        context.setCommunicationStyle(preferences.map(UserPreferencesDto::getCommunicationStyle).orElse(null));
        context.setFavoriteBrowser(preferences.map(UserPreferencesDto::getFavoriteBrowser).orElse(null));
        context.setFavoriteIde(preferences.map(UserPreferencesDto::getFavoriteIde).orElse(null));
        context.setFavoriteMusicService(preferences.map(UserPreferencesDto::getFavoriteMusicService).orElse(null));
        context.setGoals(goals);
        context.setHabits(habits);
        context.setPriorities(priorities);
        return context;
    }

    @Transactional(readOnly = true)
    public List<UserGoalDto> getGoals(String userId) {
        requireProfile(userId);
        return userGoalRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing((UserGoal goal) -> isActive(goal.getStatus()) ? 0 : 1)
                        .thenComparing(UserGoal::getDeadline, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserGoal::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toGoalDto)
                .toList();
    }

    @Transactional
    public UserGoalDto createGoal(String userId, UserGoalDto dto) {
        requireProfile(userId);
        UserGoal goal = new UserGoal();
        goal.setUserId(userId);
        updateGoal(goal, dto);
        return toGoalDto(userGoalRepository.save(goal));
    }

    @Transactional(readOnly = true)
    public List<UserHabitDto> getHabits(String userId) {
        requireProfile(userId);
        return userHabitRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing((UserHabit habit) -> normalized(habit.getTimeOfDay()))
                        .thenComparing(UserHabit::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toHabitDto)
                .toList();
    }

    @Transactional
    public UserHabitDto createHabit(String userId, UserHabitDto dto) {
        requireProfile(userId);
        UserHabit habit = new UserHabit();
        habit.setUserId(userId);
        updateHabit(habit, dto);
        return toHabitDto(userHabitRepository.save(habit));
    }

    @Transactional(readOnly = true)
    public List<UserPriorityDto> getPriorities(String userId) {
        requireProfile(userId);
        return userPriorityRepository.findByUserId(userId).stream()
                .sorted(Comparator
                        .comparing(UserPriority::getLevel, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserPriority::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toPriorityDto)
                .toList();
    }

    @Transactional
    public UserPriorityDto createPriority(String userId, UserPriorityDto dto) {
        requireProfile(userId);
        UserPriority priority = new UserPriority();
        priority.setUserId(userId);
        priority.setName(dto.getName());
        priority.setLevel(dto.getLevel());
        priority.setDescription(dto.getDescription());
        return toPriorityDto(userPriorityRepository.save(priority));
    }

    private UserProfile requireProfile(String userId) {
        provisioningService.ensureProfileExists(userId);
        return userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Profile provisioning failed for user " + userId));
    }

    private void updateGoal(UserGoal goal, UserGoalDto dto) {
        goal.setTitle(dto.getTitle());
        goal.setDescription(dto.getDescription());
        goal.setCategory(dto.getCategory());
        goal.setTargetValue(dto.getTargetValue());
        goal.setCurrentValue(dto.getCurrentValue() != null ? dto.getCurrentValue() : BigDecimal.ZERO);
        goal.setTargetDate(dto.getTargetDate());
        goal.setDeadline(dto.getDeadline());
        goal.setStatus(normalizedGoalStatus(dto.getStatus()));
    }

    private void updateHabit(UserHabit habit, UserHabitDto dto) {
        habit.setName(dto.getName());
        habit.setDescription(dto.getDescription());
        habit.setFrequency(dto.getFrequency());
        habit.setTimeOfDay(dto.getTimeOfDay());
        habit.setReminderEnabled(dto.getReminderEnabled());
        habit.setStreakDays(dto.getStreakDays());
        habit.setLastCompletedDate(dto.getLastCompletedDate());
    }

    private UserGoalDto toGoalDto(UserGoal goal) {
        return new UserGoalDto(
                goal.getId(),
                goal.getUserId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getCategory(),
                goal.getTargetValue(),
                goal.getCurrentValue(),
                goal.getTargetDate(),
                goal.getStatus(),
                goal.getDeadline(),
                goal.getCreatedAt(),
                goal.getUpdatedAt());
    }

    private UserHabitDto toHabitDto(UserHabit habit) {
        return new UserHabitDto(
                habit.getId(),
                habit.getUserId(),
                habit.getName(),
                habit.getDescription(),
                habit.getFrequency(),
                habit.getTimeOfDay(),
                habit.getReminderEnabled(),
                habit.getStreakDays(),
                habit.getLastCompletedDate(),
                habit.getCreatedAt(),
                habit.getUpdatedAt());
    }

    private UserPriorityDto toPriorityDto(UserPriority priority) {
        return new UserPriorityDto(
                priority.getId(),
                priority.getUserId(),
                priority.getName(),
                priority.getLevel(),
                priority.getDescription(),
                priority.getUpdatedAt());
    }

    private String normalizedGoalStatus(String status) {
        String normalized = normalized(status);
        return normalized == null ? "active" : normalized;
    }

    private boolean isActive(String status) {
        String normalized = normalized(status);
        return normalized == null || (!normalized.equals("completed") && !normalized.equals("abandoned"));
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
