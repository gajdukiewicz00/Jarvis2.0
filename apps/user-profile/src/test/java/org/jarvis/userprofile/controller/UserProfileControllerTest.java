package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.dto.PlanningContextDto;
import org.jarvis.userprofile.dto.UserGoalDto;
import org.jarvis.userprofile.dto.UserHabitDto;
import org.jarvis.userprofile.dto.UserPriorityDto;
import org.jarvis.userprofile.service.UserProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

    @Mock
    private UserProfileService userProfileService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUserGoalEnsuresProfileExistsAndUsesAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-7", null, List.of())
        );

        UserGoalDto goal = new UserGoalDto();
        goal.setTitle("Ship local runtime");
        goal.setUserId("ignored");
        UserGoalDto savedGoal = new UserGoalDto();
        savedGoal.setTitle("Ship local runtime");
        savedGoal.setUserId("user-7");
        when(userProfileService.createGoal("user-7", goal)).thenReturn(savedGoal);

        UserProfileController controller = new UserProfileController(userProfileService);

        UserGoalDto created = controller.createUserGoal("ignored-path-user", goal);

        verify(userProfileService).createGoal("user-7", goal);
        assertEquals("user-7", created.getUserId());
    }

    @Test
    void getPlanningContextUsesResolvedUserId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-8", null, List.of())
        );

        PlanningContextDto context = new PlanningContextDto();
        context.setUserId("user-8");
        context.setDisplayName("User Eight");
        when(userProfileService.getPlanningContext("user-8")).thenReturn(context);

        UserProfileController controller = new UserProfileController(userProfileService);

        PlanningContextDto result = controller.getPlanningContext("user-8");

        assertEquals("User Eight", result.getDisplayName());
        verify(userProfileService).getPlanningContext("user-8");
    }

    @Test
    void getUserGoalsReturnsGoalsForResolvedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-9", null, List.of())
        );

        UserGoalDto goal = new UserGoalDto();
        goal.setTitle("Ship local runtime");
        when(userProfileService.getGoals("user-9")).thenReturn(List.of(goal));

        UserProfileController controller = new UserProfileController(userProfileService);

        List<UserGoalDto> goals = controller.getUserGoals("user-9");

        assertEquals(1, goals.size());
        assertEquals("Ship local runtime", goals.get(0).getTitle());
    }

    @Test
    void getUserHabitsReturnsHabitsForResolvedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-10", null, List.of())
        );

        UserHabitDto habit = new UserHabitDto();
        habit.setName("Morning review");
        when(userProfileService.getHabits("user-10")).thenReturn(List.of(habit));

        UserProfileController controller = new UserProfileController(userProfileService);

        List<UserHabitDto> habits = controller.getUserHabits("user-10");

        assertEquals(1, habits.size());
        assertEquals("Morning review", habits.get(0).getName());
    }

    @Test
    void createUserHabitDelegatesToServiceWithResolvedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-11", null, List.of())
        );

        UserHabitDto habit = new UserHabitDto();
        habit.setName("Evening walk");
        UserHabitDto saved = new UserHabitDto();
        saved.setName("Evening walk");
        saved.setUserId("user-11");
        when(userProfileService.createHabit("user-11", habit)).thenReturn(saved);

        UserProfileController controller = new UserProfileController(userProfileService);

        UserHabitDto created = controller.createUserHabit("user-11", habit);

        assertEquals("user-11", created.getUserId());
        verify(userProfileService).createHabit("user-11", habit);
    }

    @Test
    void getUserPrioritiesReturnsPrioritiesForResolvedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-12", null, List.of())
        );

        UserPriorityDto priority = new UserPriorityDto();
        priority.setName("Backend");
        when(userProfileService.getPriorities("user-12")).thenReturn(List.of(priority));

        UserProfileController controller = new UserProfileController(userProfileService);

        List<UserPriorityDto> priorities = controller.getUserPriorities("user-12");

        assertEquals(1, priorities.size());
        assertEquals("Backend", priorities.get(0).getName());
    }

    @Test
    void createUserPriorityDelegatesToServiceWithResolvedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-13", null, List.of())
        );

        UserPriorityDto priority = new UserPriorityDto();
        priority.setName("Frontend");
        UserPriorityDto saved = new UserPriorityDto();
        saved.setName("Frontend");
        saved.setUserId("user-13");
        when(userProfileService.createPriority("user-13", priority)).thenReturn(saved);

        UserProfileController controller = new UserProfileController(userProfileService);

        UserPriorityDto created = controller.createUserPriority("user-13", priority);

        assertEquals("user-13", created.getUserId());
        verify(userProfileService).createPriority("user-13", priority);
    }

    @Test
    void getUserGoalsTrustsPathUserIdWhenCallDelegatedByService() {
        UsernamePasswordAuthenticationToken serviceAuth =
                new UsernamePasswordAuthenticationToken("api-gateway", null, List.of());
        serviceAuth.setDetails("delegated-by:api-gateway");
        SecurityContextHolder.getContext().setAuthentication(serviceAuth);

        UserGoalDto goal = new UserGoalDto();
        goal.setTitle("Delegated goal");
        when(userProfileService.getGoals("real-user")).thenReturn(List.of(goal));

        UserProfileController controller = new UserProfileController(userProfileService);

        List<UserGoalDto> goals = controller.getUserGoals("real-user");

        assertEquals(1, goals.size());
        verify(userProfileService).getGoals("real-user");
        verify(userProfileService, never()).getGoals("api-gateway");
    }

    @Test
    void getUserGoalsThrowsUnauthorizedWhenNotAuthenticated() {
        UserProfileController controller = new UserProfileController(userProfileService);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getUserGoals("user-14"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(userProfileService);
    }
}
