package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;
    private final String issuer;

    public JwtService(
            @Value("${jarvis.jwt.secret}") String secret,
            @Value("${jarvis.jwt.access-expiration}") long accessExpirationMs,
            @Value("${jarvis.jwt.refresh-expiration}") long refreshExpirationMs,
            @Value("${jarvis.jwt.issuer}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.issuer = issuer;
    }

    /**
     * Generate access token with short expiration (1 hour)
     */
    public String generateAccessToken(String userId, String username, String role) {
        return generateToken(userId, username, role, accessExpirationMs);
    }

    /**
     * Generate refresh token with long expiration (7 days)
     */
    public String generateRefreshToken(String userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + refreshExpirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuer(issuer)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    private String generateToken(String userId, String username, String role, long expirationMs) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .claim("type", "access")
                .issuer(issuer)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.error("JWT validation failed: {}", e.getMessage());
            throw new AuthenticationException("INVALID_TOKEN", "Invalid JWT token");
        }
    }

    public String extractUserId(String token) {
        return validateToken(token).getSubject();
    }

    public String extractUsername(String token) {
        Claims claims = validateToken(token);
        return claims.get("username", String.class);
    }

    public String extractRole(String token) {
        Claims claims = validateToken(token);
        return claims.get("role", String.class);
    }

    public boolean isRefreshToken(String token) {
        Claims claims = validateToken(token);
        String type = claims.get("type", String.class);
        return "refresh".equals(type);
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = validateToken(token);
            return claims.getExpiration().before(new Date());
        } catch (RuntimeException e) {
            return true;
        }
    }

    public long getAccessExpirationMs() {
        return accessExpirationMs;
    }
}
