package org.jarvis.userprofile.service;

import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.domain.UserHabit;
import org.jarvis.userprofile.domain.UserPriority;
import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.dto.PlanningContextDto;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.model.CommunicationStyle;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserGoalRepository userGoalRepository;

    @Mock
    private UserHabitRepository userHabitRepository;

    @Mock
    private UserPriorityRepository userPriorityRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserProfileProvisioningService provisioningService;

    @Mock
    private UserPreferencesService preferencesService;

    @Test
    void getPlanningContextAggregatesCanonicalProfileState() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-1");
        profile.setDisplayName("User One");
        profile.setTimezone("UTC");
        profile.setLanguage("en");

        UserGoal goal = new UserGoal();
        goal.setUserId("user-1");
        goal.setTitle("Ship planner cleanup");
        goal.setStatus("active");
        goal.setUpdatedAt(LocalDateTime.parse("2026-03-27T10:00:00"));

        UserHabit habit = new UserHabit();
        habit.setUserId("user-1");
        habit.setName("Morning review");
        habit.setTimeOfDay("morning");

        UserPriority priority = new UserPriority();
        priority.setUserId("user-1");
        priority.setName("Backend");
        priority.setLevel(1);

        UserPreferencesDto preferences = new UserPreferencesDto();
        preferences.setUserId("user-1");
        preferences.setTimezone("Europe/Warsaw");
        preferences.setLanguage("ru");
        preferences.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        preferences.setFavoriteIde("IntelliJ IDEA");

        when(userProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(preferencesService.getPreferences("user-1")).thenReturn(Optional.of(preferences));
        when(userGoalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(userHabitRepository.findByUserId("user-1")).thenReturn(List.of(habit));
        when(userPriorityRepository.findByUserId("user-1")).thenReturn(List.of(priority));

        UserProfileService service = new UserProfileService(
                userGoalRepository,
                userHabitRepository,
                userPriorityRepository,
                userProfileRepository,
                provisioningService,
                preferencesService);

        PlanningContextDto context = service.getPlanningContext("user-1");

        verify(provisioningService).ensureProfileExists("user-1");
        assertEquals("Europe/Warsaw", context.getTimezone());
        assertEquals("ru", context.getLanguage());
        assertEquals("IntelliJ IDEA", context.getFavoriteIde());
        assertEquals(List.of("Ship planner cleanup"),
                context.getGoals().stream().map(org.jarvis.userprofile.dto.UserGoalDto::getTitle).toList());
        assertEquals(List.of("Morning review"),
                context.getHabits().stream().map(org.jarvis.userprofile.dto.UserHabitDto::getName).toList());
        assertEquals(List.of("Backend"),
                context.getPriorities().stream().map(org.jarvis.userprofile.dto.UserPriorityDto::getName).toList());
    }
}
