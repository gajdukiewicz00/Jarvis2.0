package org.jarvis.security.controller;

import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.AuthorizationException;
import org.jarvis.security.config.GlobalExceptionHandler.UserAlreadyExistsException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.ChangePasswordRequest;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RefreshRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.model.User;
import org.jarvis.security.service.AuthService;
import org.jarvis.security.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthController} request handling: header validation,
 * the duplicate-username guard, and delegation to {@link AuthService}. Uses
 * a mocked service so each branch (including ones hard to trigger through
 * the full HTTP integration test) can be exercised directly.
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final TokenRevocationService tokenRevocationService = mock(TokenRevocationService.class);
    private final AuthController controller = new AuthController(authService, tokenRevocationService);

    @Test
    void registerThrowsUserAlreadyExistsWhenUsernameTaken() {
        RegisterRequest request = new RegisterRequest("alice", "password123", "USER");
        when(authService.usernameExists("alice")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> controller.register(request));
        verify(authService, never()).register(request);
    }

    @Test
    void registerReturnsCreatedWithTokensOnSuccess() {
        RegisterRequest request = new RegisterRequest("alice", "password123", "USER");
        AuthResponse expected = new AuthResponse("access", "refresh", 3600, "alice", "USER");
        when(authService.usernameExists("alice")).thenReturn(false);
        when(authService.register(request)).thenReturn(expected);

        ResponseEntity<AuthResponse> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void loginDelegatesToAuthServiceAndReturnsOk() {
        LoginRequest request = new LoginRequest("alice", "password123");
        AuthResponse expected = new AuthResponse("access", "refresh", 3600, "alice", "USER");
        when(authService.login(request)).thenReturn(expected);

        ResponseEntity<AuthResponse> response = controller.login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void refreshDelegatesToAuthServiceAndReturnsOk() {
        RefreshRequest request = new RefreshRequest("refresh-token");
        AuthResponse expected = new AuthResponse("access2", "refresh2", 3600, "alice", "USER");
        when(authService.refresh("refresh-token")).thenReturn(expected);

        ResponseEntity<AuthResponse> response = controller.refresh(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void logoutRevokesTokenAndReturnsNoContent() {
        RefreshRequest request = new RefreshRequest("refresh-token");

        ResponseEntity<Void> response = controller.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(authService).logout("refresh-token");
    }

    @Test
    void revokeCurrentSessionThrowsMissingTokenWhenHeaderAbsent() {
        RefreshRequest request = new RefreshRequest("refresh-token");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.revokeCurrentSession(null, request));

        assertThat(ex.getErrorCode()).isEqualTo("MISSING_TOKEN");
        verify(authService, never()).getUserFromToken(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeCurrentSessionPropagatesMismatchFromTokenRevocationService() {
        RefreshRequest request = new RefreshRequest("someone-elses-refresh-token");
        User caller = User.builder().id(1L).username("alice").role("USER").enabled(true).build();
        when(authService.getUserFromToken("caller-access-tok")).thenReturn(caller);
        when(tokenRevocationService.revokeOwnSession("caller-access-tok", "someone-elses-refresh-token", 1L))
                .thenThrow(new AuthorizationException("SESSION_MISMATCH", "Refresh token does not belong"));

        assertThrows(AuthorizationException.class,
                () -> controller.revokeCurrentSession("Bearer caller-access-tok", request));
    }

    @Test
    void revokeCurrentSessionDelegatesAndReturnsBothJtis() {
        RefreshRequest request = new RefreshRequest("caller-refresh-tok");
        User caller = User.builder().id(1L).username("alice").role("USER").enabled(true).build();
        when(authService.getUserFromToken("caller-access-tok")).thenReturn(caller);
        UUID accessJti = UUID.randomUUID();
        UUID refreshJti = UUID.randomUUID();
        when(tokenRevocationService.revokeOwnSession("caller-access-tok", "caller-refresh-tok", 1L))
                .thenReturn(new TokenRevocationService.RevokedSessionInfo(accessJti, refreshJti));

        ResponseEntity<Map<String, Object>> response =
                controller.revokeCurrentSession("Bearer caller-access-tok", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("revoked", true);
        assertThat(response.getBody()).containsEntry("accessJti", accessJti.toString());
        assertThat(response.getBody()).containsEntry("refreshJti", refreshJti.toString());
    }

    @Test
    void getCurrentUserThrowsMissingTokenWhenHeaderAbsent() {
        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.getCurrentUser(null));

        assertThat(ex.getErrorCode()).isEqualTo("MISSING_TOKEN");
    }

    @Test
    void getCurrentUserThrowsMissingTokenWhenHeaderNotBearer() {
        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.getCurrentUser("Basic abcdef"));

        assertThat(ex.getErrorCode()).isEqualTo("MISSING_TOKEN");
    }

    @Test
    void getCurrentUserThrowsEmptyTokenWhenBearerValueBlank() {
        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.getCurrentUser("Bearer "));

        assertThat(ex.getErrorCode()).isEqualTo("EMPTY_TOKEN");
    }

    @Test
    void getCurrentUserReturnsUserInfoOnSuccess() {
        User user = User.builder().id(1L).username("alice").role("USER").enabled(true).build();
        when(authService.getUserFromToken("good-token")).thenReturn(user);

        ResponseEntity<Map<String, Object>> response = controller.getCurrentUser("Bearer good-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", 1L);
        assertThat(response.getBody()).containsEntry("username", "alice");
        assertThat(response.getBody()).containsEntry("role", "USER");
        assertThat(response.getBody()).containsEntry("enabled", true);
    }

    @Test
    void changePasswordThrowsMissingTokenWhenHeaderAbsent() {
        ChangePasswordRequest request = new ChangePasswordRequest("old-password", "new-password-123");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.changePassword(null, request));

        assertThat(ex.getErrorCode()).isEqualTo("MISSING_TOKEN");
    }

    @Test
    void changePasswordThrowsEmptyTokenWhenBearerValueBlank() {
        ChangePasswordRequest request = new ChangePasswordRequest("old-password", "new-password-123");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.changePassword("Bearer   ", request));

        assertThat(ex.getErrorCode()).isEqualTo("EMPTY_TOKEN");
    }

    @Test
    void changePasswordDelegatesToAuthServiceOnSuccess() {
        ChangePasswordRequest request = new ChangePasswordRequest("old-password", "new-password-123");
        AuthResponse expected = new AuthResponse("access", "refresh", 3600, "alice", "USER");
        when(authService.changePassword("good-token", request)).thenReturn(expected);

        ResponseEntity<AuthResponse> response = controller.changePassword("Bearer good-token", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void healthReturnsUpStatus() {
        ResponseEntity<Map<String, String>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).containsEntry("service", "security-service");
    }
}
