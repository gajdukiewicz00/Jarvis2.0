package org.jarvis.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Component
public class ServiceJwtProvider {

    private final SecretKey key;
    private final JwtParser parser;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;

    public ServiceJwtProvider(
            @Value("${service.jwt.secret:}") String serviceSecret,
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${service.jwt.issuer:jarvis-internal}") String issuer,
            @Value("${service.jwt.audience:jarvis-services}") String audience,
            @Value("${service.jwt.ttl-seconds:300}") long ttlSeconds,
            @Value("${service.jwt.required:true}") boolean required) {
        String secret = (serviceSecret != null && !serviceSecret.isBlank())
                ? serviceSecret
                : jwtSecret;
        if (secret == null || secret.isBlank()) {
            if (required) {
                throw new IllegalStateException("service.jwt.secret (or jwt.secret) is required");
            }
            this.key = null;
            this.parser = null;
            this.issuer = issuer;
            this.audience = audience;
            this.ttlSeconds = ttlSeconds;
            return;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("service.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(this.key).build();
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String createToken(String serviceName, Collection<String> roles) {
        return createToken(serviceName, serviceName, roles);
    }

    public String createToken(String subject, String serviceName, Collection<String> roles) {
        if (!isEnabled()) {
            throw new IllegalStateException("Service JWT is disabled");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("token_type", "service")
                .claim("svc", serviceName)
                .claim("roles", roles == null ? List.of() : roles)
                .signWith(key)
                .compact();
    }

    public Claims parseServiceClaims(String token) {
        if (!isEnabled() || token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (!Objects.equals(issuer, claims.getIssuer())) {
                return null;
            }
            if (!audienceMatches(claims)) {
                return null;
            }
            String tokenType = claims.get("token_type", String.class);
            if (!"service".equals(tokenType)) {
                return null;
            }
            return claims;
        } catch (JwtException e) {
            return null;
        }
    }

    private boolean audienceMatches(Claims claims) {
        Object aud = claims.get("aud");
        if (aud instanceof String audString) {
            return audience.equals(audString);
        }
        if (aud instanceof Collection<?> audList) {
            return audList.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(value -> audience.equals(value.toString()));
        }
        return false;
    }
}
