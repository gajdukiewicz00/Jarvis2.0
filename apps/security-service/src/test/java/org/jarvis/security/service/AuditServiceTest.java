package org.jarvis.security.service;

import org.jarvis.security.dto.AuditEventPage;
import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.model.AuditEvent;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.repository.AuditEventRepository;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RevokedTokenRepository revokedTokenRepository;
    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(refreshTokenRepository, revokedTokenRepository, auditEventRepository);
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

    // ------------------------------------------------------------------
    // recordEvent / listEvents (persisted audit-event store)
    // ------------------------------------------------------------------

    @Test
    void recordEventPersistsRowWithProvidedFields() {
        auditService.recordEvent("SESSION_REVOKED_SELF", 7L, "USER_SESSION_REVOKED");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("SESSION_REVOKED_SELF");
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getReason()).isEqualTo("USER_SESSION_REVOKED");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void listEventsFiltersByUserTypeAndTimeRangeAndMapsResults() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        AuditEvent stored = AuditEvent.builder()
                .id(42L)
                .eventType("TOKEN_REVOKED")
                .userId(7L)
                .occurredAt(Instant.now().minusSeconds(60))
                .reason("ADMIN_REVOKED")
                .build();
        Page<AuditEvent> page = new PageImpl<>(List.of(stored), PageRequest.of(0, 20), 1);
        when(auditEventRepository.findFiltered(eq(7L), eq("TOKEN_REVOKED"), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(page);

        AuditEventPage result = auditService.listEvents(7L, "TOKEN_REVOKED", from, to, 0, 20);

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).eventType()).isEqualTo("TOKEN_REVOKED");
        assertThat(result.events().get(0).userId()).isEqualTo(7L);
        assertThat(result.events().get(0).tokenReference()).isEqualTo("42");
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void listEventsTreatsBlankEventTypeAsNoFilter() {
        when(auditEventRepository.findFiltered(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AuditEventPage result = auditService.listEvents(null, "   ", null, null, 0, 20);

        assertThat(result.events()).isEmpty();
        verify(auditEventRepository).findFiltered(isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listEventsClampsPageAndSizeToSaneBounds() {
        when(auditEventRepository.findFiltered(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        auditService.listEvents(null, null, null, null, -3, 10_000);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditEventRepository).findFiltered(isNull(), isNull(), isNull(), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
    }
}
