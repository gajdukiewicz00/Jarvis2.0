package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the user-auth JWT crypto core (issuance + validation).
 * This is the security-sensitive logic the audit flagged as under-tested.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789abcdef";
    private static final String ISSUER = "jarvis-test";
    private static final long ACCESS_MS = 3_600_000L;
    private static final long REFRESH_MS = 604_800_000L;
    private static final long ABSOLUTE_SESSION_TTL_MS = 2_592_000_000L;

    // Mockito default for a boolean-returning method is false, so unless a
    // test explicitly stubs existsById(...) -> true, every token here is
    // treated as not-revoked.
    private final RevokedTokenRepository revokedTokenRepository = mock(RevokedTokenRepository.class);
    private final JwtService jwt =
            new JwtService(revokedTokenRepository, SECRET, ACCESS_MS, REFRESH_MS, ABSOLUTE_SESSION_TTL_MS, ISSUER);

    @Test
    void accessTokenRoundTripExposesClaims() {
        String token = jwt.generateAccessToken("user-1", "alice", "ADMIN");
        Claims claims = jwt.validateAccessToken(token);

        assertThat(jwt.extractUserId(claims)).isEqualTo("user-1");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(jwt.extractTokenId(claims)).isNotNull();
    }

    @Test
    void refreshTokenRoundTripMatchesIssuedTokenId() {
        var issued = jwt.generateRefreshToken("user-1");
        Claims claims = jwt.validateRefreshToken(issued.token());

        assertThat(jwt.extractUserId(claims)).isEqualTo("user-1");
        assertThat(jwt.extractTokenId(claims)).isEqualTo(issued.tokenId());
    }

    @Test
    void accessTokenIsRejectedWhenValidatedAsRefresh() {
        String access = jwt.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateRefreshToken(access))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void refreshTokenIsRejectedWhenValidatedAsAccess() {
        String refresh = jwt.generateRefreshToken("u").token();
        assertThatThrownBy(() -> jwt.validateAccessToken(refresh))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService other = new JwtService(
                mock(RevokedTokenRepository.class),
                "another-secret-another-secret-0123456789abcdefghij",
                ACCESS_MS, REFRESH_MS, ABSOLUTE_SESSION_TTL_MS, ISSUER);
        String foreign = other.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(foreign))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(
                mock(RevokedTokenRepository.class), SECRET, -1_000L, -1_000L, ABSOLUTE_SESSION_TTL_MS, ISSUER);
        String expired = shortLived.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(expired))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        JwtService otherIssuer = new JwtService(
                mock(RevokedTokenRepository.class), SECRET, ACCESS_MS, REFRESH_MS, ABSOLUTE_SESSION_TTL_MS,
                "evil-issuer");
        String token = otherIssuer.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void getAccessExpirationMsReturnsConfiguredValue() {
        assertThat(jwt.getAccessExpirationMs()).isEqualTo(ACCESS_MS);
    }

    @Test
    void getAbsoluteSessionTtlMsReturnsConfiguredValue() {
        assertThat(jwt.getAbsoluteSessionTtlMs()).isEqualTo(ABSOLUTE_SESSION_TTL_MS);
    }

    // ------------------------------------------------------------------
    // per-jti revocation
    // ------------------------------------------------------------------

    @Test
    void revokedAccessTokenIsRejectedOnNextValidation() {
        String token = jwt.generateAccessToken("user-1", "alice", "USER");
        UUID jti = jwt.extractTokenId(jwt.validateAccessToken(token));
        when(revokedTokenRepository.existsById(jti)).thenReturn(true);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> jwt.validateAccessToken(token));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void revokedRefreshTokenIsRejectedOnNextValidation() {
        var issued = jwt.generateRefreshToken("user-1");
        when(revokedTokenRepository.existsById(issued.tokenId())).thenReturn(true);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> jwt.validateRefreshToken(issued.token()));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void nonRevokedTokenPassesRevocationCheck() {
        String token = jwt.generateAccessToken("user-1", "alice", "USER");

        assertThat(jwt.validateAccessToken(token)).isNotNull();
    }

    // ------------------------------------------------------------------
    // prepareForRevocation
    // ------------------------------------------------------------------

    @Test
    void prepareForRevocationResolvesAccessTokenTarget() {
        String token = jwt.generateAccessToken("42", "alice", "USER");

        JwtService.RevocationTarget target = jwt.prepareForRevocation(token);

        assertThat(target.tokenType()).isEqualTo("access");
        assertThat(target.userId()).isEqualTo(42L);
        assertThat(target.jti()).isNotNull();
        assertThat(target.expiresAt()).isNotNull();
    }

    @Test
    void prepareForRevocationResolvesRefreshTokenTarget() {
        var issued = jwt.generateRefreshToken("42");

        JwtService.RevocationTarget target = jwt.prepareForRevocation(issued.token());

        assertThat(target.tokenType()).isEqualTo("refresh");
        assertThat(target.userId()).isEqualTo(42L);
        assertThat(target.jti()).isEqualTo(issued.tokenId());
    }

    @Test
    void prepareForRevocationRejectsGarbageToken() {
        assertThatThrownBy(() -> jwt.prepareForRevocation("not-a-jwt"))
                .isInstanceOf(AuthenticationException.class);
    }
}
