package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.security.dto.AuditEventView;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only OWNER-facing audit trail. Merges the two places security-relevant
 * revocation events already land - rotated/logged-out/reused refresh tokens
 * in {@link RefreshTokenRepository}, and explicit admin/jti revocations in
 * {@link RevokedTokenRepository} - into one recency-ordered view, instead of
 * standing up a separate write-side audit log with its own risk of drift.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 500;

    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;

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
}
