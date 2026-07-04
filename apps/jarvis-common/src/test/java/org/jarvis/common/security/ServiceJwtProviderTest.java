package org.jarvis.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceJwtProviderTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210";

    @Test
    void missingSecretAndRequiredThrowsAtConstruction() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ServiceJwtProvider("", "", "jarvis-internal", "jarvis-services", 300, true, true));
        assertTrue(ex.getMessage().contains("service.jwt.secret"));
    }

    @Test
    void missingSecretAndNotRequiredIsDisabled() {
        ServiceJwtProvider provider = new ServiceJwtProvider("", "", "jarvis-internal", "jarvis-services",
                300, false, true);

        assertFalse(provider.isEnabled());
        assertNull(provider.parseServiceClaims("anything"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.createToken("svc", List.of()));
        assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void secretShorterThan32BytesIsRejectedRegardlessOfRequired() {
        assertThrows(IllegalStateException.class,
                () -> new ServiceJwtProvider("short-secret", "", "jarvis-internal", "jarvis-services",
                        300, false, true));
    }

    @Test
    void sharedSecretWithUserPlaneIsRejectedWhenNotAllowed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ServiceJwtProvider(SECRET, SECRET, "jarvis-internal", "jarvis-services",
                        300, true, false));
        assertTrue(ex.getMessage().contains("allow-shared-secret"));
    }

    @Test
    void fallingBackToJwtSecretIsTreatedAsSharedEvenWithoutAnExplicitServiceSecret() {
        assertThrows(IllegalStateException.class,
                () -> new ServiceJwtProvider("", SECRET, "jarvis-internal", "jarvis-services",
                        300, true, false));
    }

    @Test
    void distinctServiceSecretIsAllowedEvenWhenSharedSecretDisallowed() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, OTHER_SECRET, "jarvis-internal",
                "jarvis-services", 300, true, false);

        assertTrue(provider.isEnabled());
    }

    @Test
    void createTokenAndParseServiceClaimsRoundTrip() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);

        String token = provider.createToken("caller-service", "caller-service", List.of("ROLE_ONE", "ROLE_TWO"));
        Claims claims = provider.parseServiceClaims(token);

        assertEquals("caller-service", claims.getSubject());
        assertEquals("jarvis-internal", claims.getIssuer());
        assertEquals("service", claims.get("token_type"));
        assertEquals("caller-service", claims.get("svc"));
    }

    @Test
    void parseServiceClaimsRejectsNullOrBlankToken() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);

        assertNull(provider.parseServiceClaims(null));
        assertNull(provider.parseServiceClaims("  "));
    }

    @Test
    void parseServiceClaimsRejectsMalformedToken() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);

        assertNull(provider.parseServiceClaims("not-a-real-jwt"));
    }

    @Test
    void parseServiceClaimsRejectsWrongIssuer() {
        ServiceJwtProvider issuerA = new ServiceJwtProvider(SECRET, "", "issuer-a", "same-audience",
                300, true, true);
        ServiceJwtProvider issuerB = new ServiceJwtProvider(SECRET, "", "issuer-b", "same-audience",
                300, true, true);

        String token = issuerA.createToken("caller", List.of());

        assertNull(issuerB.parseServiceClaims(token));
    }

    @Test
    void parseServiceClaimsRejectsWrongAudience() {
        ServiceJwtProvider audienceA = new ServiceJwtProvider(SECRET, "", "same-issuer", "audience-a",
                300, true, true);
        ServiceJwtProvider audienceB = new ServiceJwtProvider(SECRET, "", "same-issuer", "audience-b",
                300, true, true);

        String token = audienceA.createToken("caller", List.of());

        assertNull(audienceB.parseServiceClaims(token));
    }

    @Test
    void parseServiceClaimsRejectsNonServiceTokenType() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String userToken = Jwts.builder()
                .issuer("jarvis-internal")
                .subject("some-user")
                .audience().add("jarvis-services").and()
                .claim("token_type", "user")
                .signWith(key)
                .compact();

        assertNull(provider.parseServiceClaims(userToken));
    }

    @Test
    void parseServiceClaimsRejectsExpiredToken() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        String expiredToken = Jwts.builder()
                .issuer("jarvis-internal")
                .subject("caller")
                .audience().add("jarvis-services").and()
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .claim("token_type", "service")
                .signWith(key)
                .compact();

        assertNull(provider.parseServiceClaims(expiredToken));
    }

    @Test
    void tokensSignedWithDifferentSecretsAreRejected() {
        ServiceJwtProvider providerA = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);
        ServiceJwtProvider providerB = new ServiceJwtProvider(OTHER_SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);

        String token = providerA.createToken("caller", List.of());

        assertNull(providerB.parseServiceClaims(token));
    }

    @Test
    void differentTokensForDifferentSubjectsAreNotEqual() {
        ServiceJwtProvider provider = new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services",
                300, true, true);

        String tokenOne = provider.createToken("caller-one", List.of());
        String tokenTwo = provider.createToken("caller-two", List.of());

        assertNotEquals(tokenOne, tokenTwo);
    }
}
