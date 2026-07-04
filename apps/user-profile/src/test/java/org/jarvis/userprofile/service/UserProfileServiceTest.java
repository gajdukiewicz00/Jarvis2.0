package org.jarvis.userprofile.service;

import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.domain.UserHabit;
import org.jarvis.userprofile.domain.UserPriority;
import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.dto.PlanningContextDto;
import org.jarvis.userprofile.dto.UserGoalDto;
import org.jarvis.userprofile.dto.UserHabitDto;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.dto.UserPriorityDto;
import org.jarvis.userprofile.model.CommunicationStyle;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private UserProfileService newService() {
        return new UserProfileService(
                userGoalRepository,
                userHabitRepository,
                userPriorityRepository,
                userProfileRepository,
                provisioningService,
                preferencesService);
    }

    private void stubExistingProfile(String userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setDisplayName(userId);
        profile.setTimezone("UTC");
        profile.setLanguage("en");
        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
    }

    @Test
    void getGoalsSortsByActiveStatusThenDeadlineThenMostRecentlyUpdated() {
        stubExistingProfile("user-goals");

        UserGoal completed = new UserGoal();
        completed.setUserId("user-goals");
        completed.setTitle("Finished goal");
        completed.setStatus("completed");
        completed.setDeadline(LocalDateTime.parse("2026-01-01T00:00:00"));

        UserGoal activeLaterDeadline = new UserGoal();
        activeLaterDeadline.setUserId("user-goals");
        activeLaterDeadline.setTitle("Active later deadline");
        activeLaterDeadline.setStatus("active");
        activeLaterDeadline.setDeadline(LocalDateTime.parse("2026-02-10T00:00:00"));

        UserGoal activeEarlierDeadline = new UserGoal();
        activeEarlierDeadline.setUserId("user-goals");
        activeEarlierDeadline.setTitle("Active earlier deadline");
        activeEarlierDeadline.setStatus("active");
        activeEarlierDeadline.setDeadline(LocalDateTime.parse("2026-02-05T00:00:00"));

        when(userGoalRepository.findByUserId("user-goals"))
                .thenReturn(List.of(completed, activeLaterDeadline, activeEarlierDeadline));

        List<UserGoalDto> goals = newService().getGoals("user-goals");

        assertEquals(
                List.of("Active earlier deadline", "Active later deadline", "Finished goal"),
                goals.stream().map(UserGoalDto::getTitle).toList());
        verify(provisioningService).ensureProfileExists("user-goals");
    }

    @Test
    void createGoalAppliesProvidedFieldsAndNormalizesStatus() {
        stubExistingProfile("user-goals-2");
        when(userGoalRepository.save(any(UserGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        UserGoalDto dto = new UserGoalDto();
        dto.setTitle("Ship cleanup");
        dto.setDescription("Refactor module");
        dto.setCategory("Work");
        dto.setTargetValue(BigDecimal.TEN);
        dto.setCurrentValue(BigDecimal.ONE);
        dto.setStatus("  Completed  ");

        UserGoalDto created = newService().createGoal("user-goals-2", dto);

        ArgumentCaptor<UserGoal> captor = ArgumentCaptor.forClass(UserGoal.class);
        verify(userGoalRepository).save(captor.capture());
        UserGoal saved = captor.getValue();
        assertEquals("user-goals-2", saved.getUserId());
        assertEquals("Ship cleanup", saved.getTitle());
        assertEquals("Work", saved.getCategory());
        assertEquals(BigDecimal.ONE, saved.getCurrentValue());
        assertEquals("completed", saved.getStatus());

        assertEquals("completed", created.getStatus());
        assertEquals("Ship cleanup", created.getTitle());
    }

    @Test
    void createGoalDefaultsStatusAndCurrentValueWhenMissing() {
        stubExistingProfile("user-goals-3");
        when(userGoalRepository.save(any(UserGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        UserGoalDto dto = new UserGoalDto();
        dto.setTitle("New goal");
        dto.setStatus("   ");
        dto.setCurrentValue(null);

        UserGoalDto created = newService().createGoal("user-goals-3", dto);

        assertEquals("active", created.getStatus());
        assertEquals(BigDecimal.ZERO, created.getCurrentValue());
    }

    @Test
    void getHabitsSortsByTimeOfDayThenName() {
        stubExistingProfile("user-habits");

        UserHabit evening = new UserHabit();
        evening.setUserId("user-habits");
        evening.setName("Evening walk");
        evening.setTimeOfDay("evening");

        UserHabit morningB = new UserHabit();
        morningB.setUserId("user-habits");
        morningB.setName("Read");
        morningB.setTimeOfDay("morning");

        UserHabit morningA = new UserHabit();
        morningA.setUserId("user-habits");
        morningA.setName("Meditate");
        morningA.setTimeOfDay("morning");

        when(userHabitRepository.findByUserId("user-habits"))
                .thenReturn(List.of(evening, morningB, morningA));

        List<UserHabitDto> habits = newService().getHabits("user-habits");

        // Service sorts by normalized(timeOfDay) alphabetically ("evening" < "morning"),
        // then by name as a tiebreaker within the same timeOfDay ("Meditate" < "Read").
        assertEquals(
                List.of("Evening walk", "Meditate", "Read"),
                habits.stream().map(UserHabitDto::getName).toList());
    }

    @Test
    void createHabitPersistsProvidedFieldsAndReturnsDto() {
        stubExistingProfile("user-habits-2");
        when(userHabitRepository.save(any(UserHabit.class))).thenAnswer(inv -> inv.getArgument(0));

        UserHabitDto dto = new UserHabitDto();
        dto.setName("Morning review");
        dto.setDescription("Review the day plan");
        dto.setFrequency("DAILY");
        dto.setTimeOfDay("morning");
        dto.setReminderEnabled(true);
        dto.setStreakDays(5);

        UserHabitDto created = newService().createHabit("user-habits-2", dto);

        ArgumentCaptor<UserHabit> captor = ArgumentCaptor.forClass(UserHabit.class);
        verify(userHabitRepository).save(captor.capture());
        UserHabit saved = captor.getValue();
        assertEquals("user-habits-2", saved.getUserId());
        assertEquals("Morning review", saved.getName());
        assertEquals("DAILY", saved.getFrequency());
        assertEquals(5, saved.getStreakDays());

        assertEquals("Morning review", created.getName());
        assertEquals(5, created.getStreakDays());
    }

    @Test
    void getPrioritiesSortsByLevelThenName() {
        stubExistingProfile("user-priorities");

        UserPriority low = new UserPriority();
        low.setUserId("user-priorities");
        low.setName("Cleanup");
        low.setLevel(5);

        UserPriority highB = new UserPriority();
        highB.setUserId("user-priorities");
        highB.setName("Frontend");
        highB.setLevel(1);

        UserPriority highA = new UserPriority();
        highA.setUserId("user-priorities");
        highA.setName("Backend");
        highA.setLevel(1);

        when(userPriorityRepository.findByUserId("user-priorities"))
                .thenReturn(List.of(low, highB, highA));

        List<UserPriorityDto> priorities = newService().getPriorities("user-priorities");

        assertEquals(
                List.of("Backend", "Frontend", "Cleanup"),
                priorities.stream().map(UserPriorityDto::getName).toList());
    }

    @Test
    void createPriorityPersistsProvidedFieldsAndReturnsDto() {
        stubExistingProfile("user-priorities-2");
        when(userPriorityRepository.save(any(UserPriority.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPriorityDto dto = new UserPriorityDto();
        dto.setName("Health");
        dto.setLevel(2);
        dto.setDescription("Stay fit");

        UserPriorityDto created = newService().createPriority("user-priorities-2", dto);

        ArgumentCaptor<UserPriority> captor = ArgumentCaptor.forClass(UserPriority.class);
        verify(userPriorityRepository).save(captor.capture());
        UserPriority saved = captor.getValue();
        assertEquals("user-priorities-2", saved.getUserId());
        assertEquals("Health", saved.getName());
        assertEquals(2, saved.getLevel());
        assertEquals("Stay fit", saved.getDescription());

        assertEquals("Health", created.getName());
        assertEquals(2, created.getLevel());
    }

    @Test
    void getGoalsThrowsIllegalStateWhenProvisioningFailsToPersistProfile() {
        when(userProfileRepository.findByUserId("ghost-user")).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newService().getGoals("ghost-user"));

        assertEquals("Profile provisioning failed for user ghost-user", ex.getMessage());
        verify(provisioningService).ensureProfileExists("ghost-user");
    }
}
