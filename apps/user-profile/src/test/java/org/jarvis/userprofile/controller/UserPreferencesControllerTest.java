package org.jarvis.userprofile.controller;

import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.service.UserPreferencesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPreferencesControllerTest {

    @Mock
    private UserPreferencesService preferencesService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    void getPreferencesReturnsExistingPreferencesForAuthenticatedUser() {
        authenticateAs("user-1");
        UserPreferencesDto existing = new UserPreferencesDto();
        existing.setUserId("user-1");
        existing.setFullName("Denis");
        when(preferencesService.getPreferences("user-1")).thenReturn(Optional.of(existing));

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<UserPreferencesDto> response = controller.getPreferences("user-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Denis", response.getBody().getFullName());
        verify(preferencesService, never()).createDefaultPreferences(any());
    }

    @Test
    void getPreferencesIgnoresMismatchedPathUserIdAndUsesAuthenticatedUser() {
        authenticateAs("user-1");
        UserPreferencesDto existing = new UserPreferencesDto();
        existing.setUserId("user-1");
        when(preferencesService.getPreferences("user-1")).thenReturn(Optional.of(existing));

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<UserPreferencesDto> response = controller.getPreferences("someone-else");

        assertEquals("user-1", response.getBody().getUserId());
        verify(preferencesService).getPreferences("user-1");
        verify(preferencesService, never()).getPreferences("someone-else");
    }

    @Test
    void getPreferencesCreatesDefaultsWhenNoneExist() {
        authenticateAs("user-2");
        UserPreferencesDto defaults = new UserPreferencesDto();
        defaults.setUserId("user-2");
        when(preferencesService.getPreferences("user-2")).thenReturn(Optional.empty());
        when(preferencesService.createDefaultPreferences("user-2")).thenReturn(defaults);

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<UserPreferencesDto> response = controller.getPreferences("user-2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("user-2", response.getBody().getUserId());
        verify(preferencesService).createDefaultPreferences("user-2");
    }

    @Test
    void getPreferencesThrowsUnauthorizedWhenNotAuthenticated() {
        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getPreferences("user-3"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(preferencesService);
    }

    @Test
    void createOrUpdatePreferencesForcesAuthenticatedUserId() {
        authenticateAs("user-4");
        UserPreferencesDto request = new UserPreferencesDto();
        request.setUserId("forged-user");
        request.setFullName("New Name");

        UserPreferencesDto saved = new UserPreferencesDto();
        saved.setUserId("user-4");
        saved.setFullName("New Name");
        when(preferencesService.createOrUpdatePreferences(any(UserPreferencesDto.class))).thenReturn(saved);

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<UserPreferencesDto> response = controller.createOrUpdatePreferences(request);

        assertEquals("user-4", request.getUserId());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("user-4", response.getBody().getUserId());
        verify(preferencesService).createOrUpdatePreferences(request);
    }

    @Test
    void updatePreferencesIgnoresPathUserIdMismatchAndUsesAuthenticatedUser() {
        authenticateAs("user-5");
        UserPreferencesDto request = new UserPreferencesDto();
        request.setFullName("Updated");

        UserPreferencesDto updated = new UserPreferencesDto();
        updated.setUserId("user-5");
        updated.setFullName("Updated");
        when(preferencesService.createOrUpdatePreferences(any(UserPreferencesDto.class))).thenReturn(updated);

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<UserPreferencesDto> response = controller.updatePreferences("someone-else", request);

        assertEquals("user-5", request.getUserId());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated", response.getBody().getFullName());
    }

    @Test
    void deletePreferencesIgnoresPathUserIdMismatchAndReturnsNoContent() {
        authenticateAs("user-6");

        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<Void> response = controller.deletePreferences("someone-else");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(preferencesService).deletePreferences("user-6");
    }

    @Test
    void healthReturnsOk() {
        UserPreferencesController controller = new UserPreferencesController(preferencesService);

        ResponseEntity<String> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());
    }
}
