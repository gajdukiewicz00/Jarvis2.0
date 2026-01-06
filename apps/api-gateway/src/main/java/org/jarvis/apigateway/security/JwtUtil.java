package org.jarvis.apigateway.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Utility for validating tokens in the API Gateway.
 * Mirrors the logic from security-service but focused on validation only.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final String issuer;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.issuer:jarvis}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
    }

    /**
     * Validate JWT token and return claims if valid.
     * 
     * @param token JWT token string
     * @return Claims from the token
     * @throws JwtException if token is invalid
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // Log expired tokens at DEBUG level - this is expected behavior
            log.debug("JWT token expired: {}", e.getMessage());
            throw e;
        } catch (JwtException e) {
            // Log other JWT errors at WARN level
            log.warn("JWT validation failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Extract username from JWT token.
     * 
     * @param token JWT token string
     * @return username (subject claim)
     */
    public String extractUsername(String token) {
        return validateToken(token).getSubject();
    }

    /**
     * Check if token is expired.
     * 
     * @param token JWT token string
     * @return true if expired, false otherwise
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = validateToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }
}
