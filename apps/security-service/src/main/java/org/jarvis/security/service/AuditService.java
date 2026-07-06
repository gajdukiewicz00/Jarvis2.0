package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.security.dto.AuditEventPage;
import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.model.AuditEvent;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.repository.AuditEventRepository;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * OWNER-facing audit trail, with two complementary views:
 *
 * <ul>
 *   <li>{@link #listRecentEvents} - read-only, merges the two places
 *   security-relevant revocation events already land: rotated/logged-out/
 *   reused refresh tokens in {@link RefreshTokenRepository}, and explicit
 *   admin/jti revocations in {@link RevokedTokenRepository}.</li>
 *   <li>{@link #listEvents} / {@link #recordEvent} - a dedicated, filterable
 *   {@link AuditEventRepository}-backed store covering the broader set of
 *   security events (login, registration, password change, session
 *   revocation, ...) that were previously only exposed as Micrometer
 *   counters via {@code SecurityMetrics.auditEvent}, not as queryable rows.
 *   {@link #recordEvent} is called alongside that existing counter hook at
 *   each security-relevant action.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 500;

    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final AuditEventRepository auditEventRepository;

    public List<AuditEventView> listRecentEvents(int limit) {
        int safeLimit = Math.max(MIN_LIMIT, Math.min(limit, MAX_LIMIT));
        Pageable page = PageRequest.of(0, safeLimit);

        List<AuditEventView> events = new ArrayList<>();
        for (RefreshToken revoked : refreshTokenRepository.findByRevokedAtIsNotNullOrderByRevokedAtDesc(page)) {
            events.add(new AuditEventView(
                    "REFRESH_TOKEN_" + safeUpper(revoked.getRevokeReason(), "REVOKED"),
                    revoked.getUserId(),
                    revoked.getTokenId().toString(),
                    revoked.getRevokedAt(),
                    revoked.getRevokeReason()));
        }
        for (RevokedToken jtiRevocation : revokedTokenRepository.findAllByOrderByRevokedAtDesc(page)) {
            events.add(new AuditEventView(
                    "JTI_REVOKED_" + jtiRevocation.getTokenType().toUpperCase(Locale.ROOT),
                    jtiRevocation.getUserId(),
                    jtiRevocation.getJti().toString(),
                    jtiRevocation.getRevokedAt(),
                    jtiRevocation.getRevokeReason()));
        }

        events.sort(Comparator.comparing(AuditEventView::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        return events.size() > safeLimit ? events.subList(0, safeLimit) : events;
    }

    private String safeUpper(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.toUpperCase(Locale.ROOT);
    }

    /**
     * Persist one row to the dedicated {@link AuditEventRepository}-backed
     * audit trail. Called alongside the existing {@code
     * SecurityMetrics.auditEvent} counter hook wherever a security-relevant
     * action already records that counter, so the OWNER-only viewer can
     * filter by user/type/time-range instead of relying on fixed-cardinality
     * metric tags alone.
     */
    @Transactional
    public void recordEvent(String eventType, Long userId, String reason) {
        auditEventRepository.save(AuditEvent.builder()
                .eventType(eventType)
                .userId(userId)
                .occurredAt(Instant.now())
                .reason(reason)
                .build());
    }

    /**
     * Page through the dedicated audit-event store, optionally filtered by
     * user, event type, and/or an occurred-at time range. {@code page} and
     * {@code size} are clamped to sane bounds the same way {@link
     * #listRecentEvents} clamps its {@code limit}.
     */
    @Transactional(readOnly = true)
    public AuditEventPage listEvents(Long userId, String eventType, Instant from, Instant to, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(MIN_LIMIT, Math.min(size, MAX_LIMIT));
        String normalizedType = (eventType == null || eventType.isBlank()) ? null : eventType.trim();

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditEvent> result = auditEventRepository.findFiltered(userId, normalizedType, from, to, pageable);

        List<AuditEventView> events = result.getContent().stream()
                .map(event -> new AuditEventView(
                        event.getEventType(),
                        event.getUserId(),
                        event.getId() == null ? null : event.getId().toString(),
                        event.getOccurredAt(),
                        event.getReason()))
                .toList();

        return new AuditEventPage(events, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
