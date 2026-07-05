package org.jarvis.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.jarvis.security.model.User;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:securitydb;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS security",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
        "jarvis.jwt.issuer=jarvis",
        "jarvis.jwt.access-expiration=600000",
        "jarvis.jwt.refresh-expiration=604800000"
})
class SecurityServiceAuthIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerAndMeFlowUsesAccessTokenOnly() throws Exception {
        AuthPair auth = register("alice", "password123", "USER");

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + auth.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + auth.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN_TYPE"));
    }

    @Test
    void refreshRotationRejectsReusedTokenAndRevokesRotatedSessionFamily() throws Exception {
        AuthPair initial = register("bob", "password123", "USER");
        AuthPair rotated = refresh(initial.refreshToken());

        assertNotEquals(initial.refreshToken(), rotated.refreshToken());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(initial.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REUSED"));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rotated.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        AuthPair auth = register("carol", "password123", "USER");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(auth.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(auth.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + auth.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("carol"));
    }

    @Test
    void changePasswordRevokesExistingRefreshTokensAndRequiresNewCredentials() throws Exception {
        AuthPair initial = register("diana", "password123", "USER");

        MvcResult result = mockMvc.perform(post("/auth/password/change")
                        .header("Authorization", "Bearer " + initial.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "password123",
                                  "newPassword": "new-password-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        AuthPair changed = parseAuthPair(result);
        assertNotEquals(initial.refreshToken(), changed.refreshToken());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(initial.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "diana",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "diana",
                                  "password": "new-password-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("diana"));
    }

    @Test
    void revokeAllForUserInvalidatesAlreadyIssuedAccessTokenViaTokensValidFrom() throws Exception {
        AuthPair target = register("ivan", "password123", "USER");
        AuthPair owner = registerOwnerDirectly("root-owner", "owner-password-1");
        Long targetUserId = userRepository.findByUsername("ivan").orElseThrow().getId();

        // Sanity: the access token is valid before any revocation.
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + target.accessToken()))
                .andExpect(status().isOk());

        // Cross a whole-second boundary before revoking. JWT `iat` has second
        // resolution, so the session floor can only distinguish the pre-revoke
        // token from the post-revoke token if they fall in different seconds.
        // A >1000ms wait guarantees the revoke floor lands in a strictly later
        // second than the already-issued access token (deterministic, not a
        // flaky async wait — it crosses the intrinsic JWT time quantum).
        Thread.sleep(1100);

        MvcResult revokeAllResult = mockMvc.perform(post("/auth/revoke-all/" + targetUserId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .param("reason", "SECURITY_INCIDENT"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode revokeAllBody = objectMapper.readTree(revokeAllResult.getResponse().getContentAsString());
        assertThat(revokeAllBody.get("userId").asLong()).isEqualTo(targetUserId.longValue());
        assertThat(revokeAllBody.get("revokedRefreshTokens").asInt()).isEqualTo(1);

        // The access token issued before revoke-all predates the new
        // tokens_valid_from floor, so it must now be rejected even though
        // access-token jtis are not individually tracked server-side.
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + target.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));

        // The refresh token is likewise revoked.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(target.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_REVOKED"));

        // The account itself is untouched: the user can still log in and get
        // a fresh token pair that is issued after the new floor.
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "ivan",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        AuthPair reloggedIn = parseAuthPair(result);

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + reloggedIn.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ivan"));
    }

    @Test
    void loginIssuesRefreshTokenBackedByServerState() throws Exception {
        register("erin", "password123", "USER");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "erin",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        AuthPair loginAuth = parseAuthPair(result);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(loginAuth.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("erin"));
    }

    @Test
    void refreshRejectsLegacyStatelessRefreshToken() throws Exception {
        AuthPair auth = register("frank", "password123", "USER");
        String userId = Jwts.parser()
                .verifyWith(testSecretKey())
                .build()
                .parseSignedClaims(auth.accessToken())
                .getPayload()
                .getSubject();

        String legacyRefreshToken = Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuer("jarvis")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(testSecretKey())
                .compact();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(legacyRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void selfServiceRegistrationCannotCreateAdmin() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "mallory",
                                  "password": "password123",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void disabledUserCannotLoginOrUseMe() throws Exception {
        AuthPair auth = register("grace", "password123", "USER");
        disableUser("grace");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "grace",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ACCOUNT_DISABLED"));

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + auth.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ACCOUNT_DISABLED"));
    }

    @Test
    void disabledUserRefreshAttemptRevokesAllOutstandingRefreshSessions() throws Exception {
        AuthPair initial = register("heidi", "password123", "USER");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "heidi",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk());

        disableUser("heidi");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(initial.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("ACCOUNT_DISABLED"));

        assertTrue(refreshTokenRepository.findAll().stream()
                .allMatch(token -> token.getRevokedAt() != null
                        && "ACCOUNT_DISABLED".equals(token.getRevokeReason())));
    }

    private AuthPair register(String username, String password, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s",
                                  "role": "%s"
                                }
                                """.formatted(username, password, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return parseAuthPair(result);
    }

    /**
     * Seeds an OWNER user directly through the repository (self-service
     * registration is USER-only, see {@code selfServiceRegistrationCannotCreateAdmin})
     * and then logs in over real HTTP so the returned token pair is a
     * genuine, server-tracked session like any other.
     */
    private AuthPair registerOwnerDirectly(String username, String password) throws Exception {
        User owner = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role("OWNER")
                .enabled(true)
                .build();
        userRepository.save(owner);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return parseAuthPair(result);
    }

    private AuthPair refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        return parseAuthPair(result);
    }

    private AuthPair parseAuthPair(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new AuthPair(
                body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    private String refreshBody(String refreshToken) {
        return """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken);
    }

    private SecretKey testSecretKey() {
        return Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private void disableUser(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);
    }

    private record AuthPair(String accessToken, String refreshToken) {
    }
}
