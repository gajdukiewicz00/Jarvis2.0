package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.dto.UserGoalDto;
import org.jarvis.userprofile.service.UserProfileService;
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
}
