package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * User-auth JWT service for Jarvis.
 *
 * <p>This service owns access/refresh token issuance and validation for the user-auth plane only.
 * It does not issue or validate internal service JWTs.</p>
 *
 * <p>Rotation is single-key only. The implementation reads one active HMAC secret from
 * {@code jarvis.jwt.secret} and does not support {@code kid}, key rings, or multi-key validation.</p>
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;
    private final String issuer;

    public JwtService(
            @Value("${jarvis.jwt.secret}") String secret,
            @Value("${jarvis.jwt.access-expiration}") long accessExpirationMs,
            @Value("${jarvis.jwt.refresh-expiration}") long refreshExpirationMs,
            @Value("${jarvis.jwt.issuer}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build();
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.issuer = issuer;
    }

    /**
     * Generate access token with short expiration (1 hour)
     */
    public String generateAccessToken(String userId, String username, String role) {
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(accessExpirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .claim("roles", List.of(role))
                .claim("type", "access")
                .claim("token_type", "access")
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generate refresh token with long expiration (7 days)
     */
    public IssuedRefreshToken generateRefreshToken(String userId) {
        UUID tokenId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(refreshExpirationMs);

        String token = Jwts.builder()
                .id(tokenId.toString())
                .subject(userId)
                .claim("type", "refresh")
                .claim("token_type", "refresh")
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();

        return new IssuedRefreshToken(tokenId, token, now, expiration);
    }

    public Claims validateAccessToken(String token) {
        Claims claims = parseClaims(token);
        ensureTokenType(claims, "access", "Access token is required");
        return claims;
    }

    public Claims validateRefreshToken(String token) {
        Claims claims = parseClaims(token);
        ensureTokenType(claims, "refresh", "Refresh token is required");
        return claims;
    }

    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    public UUID extractTokenId(Claims claims) {
        String tokenId = claims.getId();
        if (tokenId == null || tokenId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(tokenId);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("INVALID_TOKEN", "Invalid token identifier");
        }
    }

    private Claims parseClaims(String token) {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthenticationException("TOKEN_EXPIRED", "Token has expired");
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            throw new AuthenticationException("INVALID_TOKEN", "Invalid JWT token");
        }
    }

    private void ensureTokenType(Claims claims, String expectedType, String message) {
        String legacyType = claims.get("type", String.class);
        String canonicalType = claims.get("token_type", String.class);
        if (!expectedType.equals(legacyType) && !expectedType.equals(canonicalType)) {
            throw new AuthenticationException("INVALID_TOKEN_TYPE", message);
        }
    }

    public long getAccessExpirationMs() {
        return accessExpirationMs;
    }

    public record IssuedRefreshToken(
            UUID tokenId,
            String token,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
