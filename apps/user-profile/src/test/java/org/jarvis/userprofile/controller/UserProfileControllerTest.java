package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.domain.UserGoal;
import org.jarvis.userprofile.repository.UserGoalRepository;
import org.jarvis.userprofile.repository.UserHabitRepository;
import org.jarvis.userprofile.repository.UserPriorityRepository;
import org.jarvis.userprofile.service.UserProfileProvisioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

    @Mock
    private UserGoalRepository userGoalRepository;

    @Mock
    private UserHabitRepository userHabitRepository;

    @Mock
    private UserPriorityRepository userPriorityRepository;

    @Mock
    private UserProfileProvisioningService userProfileProvisioningService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUserGoalEnsuresProfileExistsAndUsesAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-7", null, List.of())
        );

        UserGoal goal = new UserGoal();
        goal.setTitle("Ship local runtime");
        when(userGoalRepository.save(goal)).thenReturn(goal);

        UserProfileController controller = new UserProfileController(
                userGoalRepository,
                userHabitRepository,
                userPriorityRepository,
                userProfileProvisioningService
        );

        UserGoal created = controller.createUserGoal("ignored-path-user", goal);

        verify(userProfileProvisioningService).ensureProfileExists("user-7");
        verify(userGoalRepository).save(goal);
        assertEquals("user-7", created.getUserId());
    }
}
