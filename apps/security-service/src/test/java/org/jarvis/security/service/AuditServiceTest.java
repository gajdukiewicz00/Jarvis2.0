package org.jarvis.security.service;

import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RevokedTokenRepository revokedTokenRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(refreshTokenRepository, revokedTokenRepository);
    }

    @Test
    void listRecentEventsMergesAndOrdersBothSourcesByRecency() {
        Instant older = Instant.now().minusSeconds(600);
        Instant newer = Instant.now().minusSeconds(10);

        RefreshToken rotated = RefreshToken.builder()
                .tokenId(UUID.randomUUID())
                .userId(1L)
                .revokedAt(older)
                .revokeReason("REFRESH_ROTATED")
                .build();
        when(refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(any()))
                .thenReturn(List.of(rotated));

        RevokedToken jtiRevoked = RevokedToken.builder()
                .jti(UUID.randomUUID())
                .tokenType("access")
                .userId(2L)
                .revokedAt(newer)
                .revokeReason("ADMIN_REVOKED")
                .build();
        when(revokedTokenRepository.findAllByOrderByRevokedAtDesc(any()))
                .thenReturn(List.of(jtiRevoked));

        List<AuditEventView> events = auditService.listRecentEvents(50);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).occurredAt()).isEqualTo(newer);
        assertThat(events.get(0).eventType()).isEqualTo("JTI_REVOKED_ACCESS");
        assertThat(events.get(0).userId()).isEqualTo(2L);
        assertThat(events.get(1).occurredAt()).isEqualTo(older);
        assertThat(events.get(1).eventType()).isEqualTo("REFRESH_TOKEN_REFRESH_ROTATED");
        assertThat(events.get(1).userId()).isEqualTo(1L);
    }

    @Test
    void listRecentEventsTruncatesToRequestedLimitAfterMerging() {
        Instant t1 = Instant.now().minusSeconds(10);
        Instant t2 = Instant.now().minusSeconds(20);
        RefreshToken a = RefreshToken.builder().tokenId(UUID.randomUUID()).userId(1L)
                .revokedAt(t1).revokeReason("USER_LOGOUT").build();
        RefreshToken b = RefreshToken.builder().tokenId(UUID.randomUUID()).userId(1L)
                .revokedAt(t2).revokeReason("USER_LOGOUT").build();
        when(refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(any()))
                .thenReturn(List.of(a, b));
        when(revokedTokenRepository.findAllByOrderByRevokedAtDesc(any())).thenReturn(List.of());

        List<AuditEventView> events = auditService.listRecentEvents(1);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).occurredAt()).isEqualTo(t1);
    }

    @Test
    void listRecentEventsClampsLimitToUpperBound() {
        when(refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(any())).thenReturn(List.of());
        when(revokedTokenRepository.findAllByOrderByRevokedAtDesc(any())).thenReturn(List.of());

        List<AuditEventView> events = auditService.listRecentEvents(10_000);

        assertThat(events).isEmpty();
    }

    @Test
    void listRecentEventsClampsLimitToLowerBound() {
        when(refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(any())).thenReturn(List.of());
        when(revokedTokenRepository.findAllByOrderByRevokedAtDesc(any())).thenReturn(List.of());

        List<AuditEventView> events = auditService.listRecentEvents(-5);

        assertThat(events).isEmpty();
    }

    @Test
    void listRecentEventsFallsBackToReasonlessLabelWhenRevokeReasonMissing() {
        RefreshToken noReason = RefreshToken.builder()
                .tokenId(UUID.randomUUID())
                .userId(1L)
                .revokedAt(Instant.now())
                .build();
        when(refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(any()))
                .thenReturn(List.of(noReason));
        when(revokedTokenRepository.findAllByOrderByRevokedAtDesc(any())).thenReturn(List.of());

        List<AuditEventView> events = auditService.listRecentEvents(10);

        assertThat(events.get(0).eventType()).isEqualTo("REFRESH_TOKEN_REVOKED");
    }
}
