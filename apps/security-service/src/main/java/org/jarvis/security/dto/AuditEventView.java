package org.jarvis.security.dto;

import java.time.Instant;

/**
 * A single normalized security-audit entry surfaced by the OWNER-only audit
 * viewer. Backed by existing revocation bookkeeping (refresh-token rotation
 * history and explicit jti revocations) rather than a separate write-side
 * audit log, so there is exactly one source of truth for "what got revoked
 * and why".
 */
public record AuditEventView(
        String eventType,
        Long userId,
        String tokenReference,
        Instant occurredAt,
        String reason) {
}
