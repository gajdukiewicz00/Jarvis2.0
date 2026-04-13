package org.jarvis.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JwtUtil} — focused on issuer enforcement defaults.
 */
class JwtUtilTest {

    private static final String SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";
    private static final String ISSUER = "jarvis";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    // ── enforce-issuer = true (default) ────────────────────────────

    @Test
    void defaultEnforcement_tokenWithCorrectIssuer_isAccepted() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildToken(ISSUER, Instant.now().plusSeconds(300), "access");

        Claims claims = util.validateToken(token);

        assertEquals("user-1", claims.getSubject());
        assertEquals(ISSUER, claims.getIssuer());
    }

    @Test
    void defaultEnforcement_tokenWithWrongIssuer_isRejected() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildToken("evil-issuer", Instant.now().plusSeconds(300), "access");

        JwtException ex = assertThrows(JwtException.class, () -> util.validateToken(token));
        assertTrue(ex instanceof IncorrectClaimException,
                "Expected IncorrectClaimException but got: " + ex.getClass().getSimpleName());
    }

    @Test
    void defaultEnforcement_tokenWithNoIssuer_isRejected() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildTokenNoIssuer(Instant.now().plusSeconds(300));

        assertThrows(JwtException.class, () -> util.validateToken(token));
    }

    // ── enforce-issuer = false (explicit override) ──────────────────

    @Test
    void withoutEnforcement_tokenWithWrongIssuer_isAccepted() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, false);
        String token = buildToken("wrong-issuer", Instant.now().plusSeconds(300), "access");

        Claims claims = util.validateToken(token);

        assertEquals("user-1", claims.getSubject());
        assertEquals("wrong-issuer", claims.getIssuer());
    }

    @Test
    void withoutEnforcement_tokenWithNoIssuer_isAccepted() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, false);
        String token = buildTokenNoIssuer(Instant.now().plusSeconds(300));

        Claims claims = util.validateToken(token);

        assertEquals("user-1", claims.getSubject());
        assertNull(claims.getIssuer());
    }

    @Test
    void accessTokenTypeCheckRejectsRefreshTokenClaims() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        Claims claims = util.validateToken(buildToken(ISSUER, Instant.now().plusSeconds(300), "refresh"));

        assertFalse(util.isAccessToken(claims));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String buildToken(String issuer, Instant expiresAt, String tokenType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", "USER")
                .claim("type", tokenType)
                .issuer(issuer)
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    private String buildTokenNoIssuer(Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", "USER")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}
