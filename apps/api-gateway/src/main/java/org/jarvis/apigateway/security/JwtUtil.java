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
 * <p>
 * Supports issuer enforcement via {@code jarvis.jwt.enforce-issuer=true}.
 * The default is enabled so gateway-side validation matches the issuer contract
 * emitted by {@code security-service}.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final String issuer;
    private final boolean enforceIssuer;

    public JwtUtil(
            @Value("${jarvis.jwt.secret}") String secret,
            @Value("${jarvis.jwt.issuer:jarvis}") String issuer,
            @Value("${jarvis.jwt.enforce-issuer:true}") boolean enforceIssuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.enforceIssuer = enforceIssuer;
        if (enforceIssuer) {
            log.info("JwtUtil: issuer enforcement ENABLED (expected issuer: {})", issuer);
        }
    }

    /**
     * Validate JWT token and return claims if valid.
     * <p>
     * If {@code jarvis.jwt.enforce-issuer=true}, the parser will require
     * the token's issuer to match the configured issuer value.
     *
     * @param token JWT token string
     * @return Claims from the token
     * @throws JwtException if token is invalid
     */
    public Claims validateToken(String token) {
        try {
            JwtParserBuilder parserBuilder = Jwts.parser()
                    .verifyWith(secretKey);

            if (enforceIssuer) {
                parserBuilder.requireIssuer(issuer);
            }

            return parserBuilder
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // Log expired tokens at DEBUG level - this is expected behavior.
            // Message is passed through TokenMaskingUtil as defense-in-depth: jjwt
            // exception messages are not expected to embed the raw token, but this
            // ensures one never reaches the logs even if that assumption is violated.
            log.debug("JWT token expired: {}", TokenMaskingUtil.maskTokensInText(e.getMessage()));
            throw e;
        } catch (JwtException e) {
            // Log other JWT errors at WARN level
            log.warn("JWT validation failed: {}", TokenMaskingUtil.maskTokensInText(e.getMessage()));
            throw e;
        }
    }

    public boolean isAccessToken(Claims claims) {
        if (claims == null) {
            return false;
        }

        String legacyType = claims.get("type", String.class);
        String canonicalType = claims.get("token_type", String.class);
        return "access".equals(legacyType) || "access".equals(canonicalType);
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
        } catch (RuntimeException e) {
            return true;
        }
    }
}
