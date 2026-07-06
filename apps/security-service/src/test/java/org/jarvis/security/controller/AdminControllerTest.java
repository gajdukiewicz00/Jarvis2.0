package org.jarvis.security.controller;

import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.AuthorizationException;
import org.jarvis.security.dto.AuditEventPage;
import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.dto.RevokeRequest;
import org.jarvis.security.model.User;
import org.jarvis.security.service.AuditService;
import org.jarvis.security.service.AuthService;
import org.jarvis.security.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminController}: OWNER-only header/role gating and
 * delegation to {@link TokenRevocationService} / {@link AuditService}. Uses
 * mocked collaborators, mirroring {@code AuthControllerTest}.
 */
class AdminControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final TokenRevocationService tokenRevocationService = mock(TokenRevocationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminController controller =
            new AdminController(authService, tokenRevocationService, auditService);

    private User owner(long id) {
        return User.builder().id(id).username("owner1").role("OWNER").enabled(true).build();
    }

    @Test
    void revokeThrowsMissingTokenWhenHeaderAbsent() {
        RevokeRequest request = new RevokeRequest("some-token", "reason");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> controller.revoke(null, request));

        assertThat(ex.getErrorCode()).isEqualTo("MISSING_TOKEN");
        verify(authService, never()).requireRole(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeThrowsForbiddenWhenCallerIsNotOwner() {
        RevokeRequest request = new RevokeRequest("some-token", "reason");
        when(authService.requireRole("caller-tok", "OWNER"))
                .thenThrow(new AuthorizationException("FORBIDDEN_ROLE", "Requires OWNER"));

        assertThrows(AuthorizationException.class, () -> controller.revoke("Bearer caller-tok", request));
    }

    @Test
    void revokeDelegatesToTokenRevocationServiceForOwner() {
        RevokeRequest request = new RevokeRequest("target-token", "SECURITY_INCIDENT");
        User owner = owner(1L);
        when(authService.requireRole("caller-tok", "OWNER")).thenReturn(owner);
        UUID jti = UUID.randomUUID();
        when(tokenRevocationService.revokeToken("target-token", "SECURITY_INCIDENT", 1L))
                .thenReturn(new TokenRevocationService.RevokedTokenInfo(jti, "access"));

        ResponseEntity<Map<String, Object>> response = controller.revoke("Bearer caller-tok", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("revoked", true);
        assertThat(response.getBody()).containsEntry("jti", jti.toString());
        assertThat(response.getBody()).containsEntry("tokenType", "access");
    }

    @Test
    void revokeAllThrowsForbiddenWhenCallerIsNotOwner() {
        when(authService.requireRole("caller-tok", "OWNER"))
                .thenThrow(new AuthorizationException("FORBIDDEN_ROLE", "Requires OWNER"));

        assertThrows(AuthorizationException.class,
                () -> controller.revokeAll("Bearer caller-tok", 42L, "reason"));
    }

    @Test
    void revokeAllDelegatesToTokenRevocationServiceForOwner() {
        User owner = owner(1L);
        when(authService.requireRole("caller-tok", "OWNER")).thenReturn(owner);
        when(tokenRevocationService.revokeAllForUser(42L, "OFFBOARDING")).thenReturn(5);

        ResponseEntity<Map<String, Object>> response =
                controller.revokeAll("Bearer caller-tok", 42L, "OFFBOARDING");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("userId", 42L);
        assertThat(response.getBody()).containsEntry("revokedRefreshTokens", 5);
    }

    @Test
    void auditThrowsForbiddenWhenCallerIsNotOwner() {
        when(authService.requireRole("caller-tok", "OWNER"))
                .thenThrow(new AuthorizationException("FORBIDDEN_ROLE", "Requires OWNER"));

        assertThrows(AuthorizationException.class, () -> controller.audit("Bearer caller-tok", 50));
    }

    @Test
    void auditReturnsRecentEventsForOwner() {
        when(authService.requireRole("caller-tok", "OWNER")).thenReturn(owner(1L));
        AuditEventView event = new AuditEventView("JTI_REVOKED_ACCESS", 2L, "some-jti",
                Instant.now(), "ADMIN_REVOKED");
        when(auditService.listRecentEvents(25)).thenReturn(List.of(event));

        ResponseEntity<List<AuditEventView>> response = controller.audit("Bearer caller-tok", 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(event);
    }

    @Test
    void auditEventsThrowsForbiddenWhenCallerIsNotOwner() {
        when(authService.requireRole("caller-tok", "OWNER"))
                .thenThrow(new AuthorizationException("FORBIDDEN_ROLE", "Requires OWNER"));

        assertThrows(AuthorizationException.class,
                () -> controller.auditEvents("Bearer caller-tok", 2L, "TOKEN_REVOKED", null, null, 0, 25));
        verify(auditService, never()).listEvents(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void auditEventsDelegatesToAuditServiceWithFiltersForOwner() {
        when(authService.requireRole("caller-tok", "OWNER")).thenReturn(owner(1L));
        AuditEventView event = new AuditEventView("TOKEN_REVOKED", 2L, "42", Instant.now(), "ADMIN_REVOKED");
        AuditEventPage page = new AuditEventPage(List.of(event), 0, 25, 1, 1);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        when(auditService.listEvents(2L, "TOKEN_REVOKED", from, to, 0, 25)).thenReturn(page);

        ResponseEntity<AuditEventPage> response =
                controller.auditEvents("Bearer caller-tok", 2L, "TOKEN_REVOKED", from, to, 0, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(page);
    }
}
