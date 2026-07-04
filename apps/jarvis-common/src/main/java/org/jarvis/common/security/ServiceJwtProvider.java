package org.jarvis.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Service-to-service JWT provider for Jarvis' internal trust plane.
 * Creates and validates internal JWT tokens without consulting {@code security-service}.
 *
 * <p>This is intentionally separate from user JWT handling:
 * {@code security-service} owns the user-auth plane, while this provider owns the
 * shared internal service-token contract used by runtime services.</p>
 *
 * <p>Rotation is single-key only. The provider reads one active HMAC secret from
 * configuration and does not implement {@code kid}, key rings, or multi-key validation.</p>
 *
 * <p>Registered as a bean by {@link org.jarvis.common.JarvisCommonAutoConfiguration}.</p>
 */
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
            @Value("${service.jwt.required:true}") boolean required,
            @Value("${service.jwt.allow-shared-secret:true}") boolean allowSharedSecret) {
        boolean serviceSecretProvided = serviceSecret != null && !serviceSecret.isBlank();
        String secret = serviceSecretProvided ? serviceSecret : jwtSecret;
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
        // Hardening (SECURITY_HARDENING_PLAN.md F-002): when service-plane and user-plane
        // share the same HMAC secret, a single compromise breaks both auth planes. Production
        // profiles should set service.jwt.allow-shared-secret=false to reject this.
        boolean sharedWithUserPlane = !serviceSecretProvided
                || (jwtSecret != null && secret.equals(jwtSecret));
        if (sharedWithUserPlane && !allowSharedSecret) {
            throw new IllegalStateException(
                    "service.jwt.secret must be set independently of jwt.secret when "
                            + "service.jwt.allow-shared-secret=false. Generate a distinct value with "
                            + "`openssl rand -base64 48` and set SERVICE_JWT_SECRET in your environment.");
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
