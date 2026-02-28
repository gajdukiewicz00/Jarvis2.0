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
 * Unit tests for {@link JwtUtil} — focused on issuer enforcement.
 */
class JwtUtilTest {

    private static final String SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";
    private static final String ISSUER = "jarvis";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    // ── enforce-issuer = false (default) ────────────────────────────

    @Test
    void withoutEnforcement_tokenWithWrongIssuer_isAccepted() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, false);
        String token = buildToken("wrong-issuer", Instant.now().plusSeconds(300));

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

    // ── enforce-issuer = true ───────────────────────────────────────

    @Test
    void withEnforcement_tokenWithCorrectIssuer_isAccepted() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildToken(ISSUER, Instant.now().plusSeconds(300));

        Claims claims = util.validateToken(token);

        assertEquals("user-1", claims.getSubject());
        assertEquals(ISSUER, claims.getIssuer());
    }

    @Test
    void withEnforcement_tokenWithWrongIssuer_isRejected() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildToken("evil-issuer", Instant.now().plusSeconds(300));

        JwtException ex = assertThrows(JwtException.class, () -> util.validateToken(token));
        assertTrue(ex instanceof IncorrectClaimException,
                "Expected IncorrectClaimException but got: " + ex.getClass().getSimpleName());
    }

    @Test
    void withEnforcement_tokenWithNoIssuer_isRejected() {
        JwtUtil util = new JwtUtil(SECRET, ISSUER, true);
        String token = buildTokenNoIssuer(Instant.now().plusSeconds(300));

        assertThrows(JwtException.class, () -> util.validateToken(token));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String buildToken(String issuer, Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "test-user")
                .claim("roles", "USER")
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

