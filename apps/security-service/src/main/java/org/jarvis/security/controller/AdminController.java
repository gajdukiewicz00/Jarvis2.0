package org.jarvis.security.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.dto.AuditEventPage;
import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.dto.RevokeRequest;
import org.jarvis.security.model.Role;
import org.jarvis.security.model.User;
import org.jarvis.security.service.AuditService;
import org.jarvis.security.service.AuthService;
import org.jarvis.security.service.TokenRevocationService;
import org.jarvis.security.util.BearerTokenExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OWNER-only administrative endpoints: single-token and whole-session
 * revocation, and the security audit viewer.
 *
 * <p>All authorization here is enforced at the controller level (this
 * service has no method-security filter chain - {@code /auth/**} is
 * {@code permitAll()} in {@code SecurityConfig}), mirroring the pattern
 * already used by {@link AuthController#changePassword}.</p>
 *
 * <ul>
 *   <li>POST /auth/revoke - revoke a single access or refresh token by value (OWNER)</li>
 *   <li>POST /auth/revoke-all/{userId} - revoke every session for a user (OWNER)</li>
 *   <li>GET  /auth/audit - list recent revocation/security events, limit only (OWNER)</li>
 *   <li>GET  /auth/audit/events - page/filter the persisted audit-event store by
 *       user/type/time-range (OWNER)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;
    private final TokenRevocationService tokenRevocationService;
    private final AuditService auditService;

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revoke(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody RevokeRequest request) {

        User owner = requireOwner(authHeader);
        TokenRevocationService.RevokedTokenInfo info =
                tokenRevocationService.revokeToken(request.token(), request.reason(), owner.getId());

        log.info("Owner '{}' revoked {} token {}", owner.getUsername(), info.tokenType(), info.jti());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revoked", true);
        body.put("jti", info.jti().toString());
        body.put("tokenType", info.tokenType());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/revoke-all/{userId}")
    public ResponseEntity<Map<String, Object>> revokeAll(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId,
            @RequestParam(required = false) String reason) {

        User owner = requireOwner(authHeader);
        int revokedRefreshTokens = tokenRevocationService.revokeAllForUser(userId, reason);

        log.info("Owner '{}' revoked all sessions for user {}", owner.getUsername(), userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("revokedRefreshTokens", revokedRefreshTokens);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/audit")
    public ResponseEntity<List<AuditEventView>> audit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "50") int limit) {

        requireOwner(authHeader);
        return ResponseEntity.ok(auditService.listRecentEvents(limit));
    }

    /**
     * Page/filter the dedicated audit-event store (see {@link
     * AuditService#listEvents}). {@code from}/{@code to} accept ISO-8601
     * instants (e.g. {@code 2026-07-01T00:00:00Z}); any filter parameter may
     * be omitted.
     */
    @GetMapping("/audit/events")
    public ResponseEntity<AuditEventPage> auditEvents(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        requireOwner(authHeader);
        return ResponseEntity.ok(auditService.listEvents(userId, eventType, from, to, page, size));
    }

    private User requireOwner(String authHeader) {
        String token = BearerTokenExtractor.extract(authHeader);
        return authService.requireRole(token, Role.OWNER.name());
    }
}
