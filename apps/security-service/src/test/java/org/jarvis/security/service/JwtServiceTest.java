package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the user-auth JWT crypto core (issuance + validation).
 * This is the security-sensitive logic the audit flagged as under-tested.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789abcdef";
    private static final String ISSUER = "jarvis-test";
    private static final long ACCESS_MS = 3_600_000L;
    private static final long REFRESH_MS = 604_800_000L;

    private final JwtService jwt = new JwtService(SECRET, ACCESS_MS, REFRESH_MS, ISSUER);

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
                "another-secret-another-secret-0123456789abcdefghij", ACCESS_MS, REFRESH_MS, ISSUER);
        String foreign = other.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(foreign))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(SECRET, -1_000L, -1_000L, ISSUER);
        String expired = shortLived.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(expired))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        JwtService otherIssuer = new JwtService(SECRET, ACCESS_MS, REFRESH_MS, "evil-issuer");
        String token = otherIssuer.generateAccessToken("u", "a", "USER");
        assertThatThrownBy(() -> jwt.validateAccessToken(token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void getAccessExpirationMsReturnsConfiguredValue() {
        assertThat(jwt.getAccessExpirationMs()).isEqualTo(ACCESS_MS);
    }
}
