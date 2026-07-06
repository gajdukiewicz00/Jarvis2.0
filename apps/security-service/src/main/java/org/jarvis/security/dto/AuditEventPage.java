package org.jarvis.security.dto;

import java.util.List;

/**
 * One page of {@link AuditEventView} results from the OWNER-only audit
 * viewer, plus paging metadata (see {@code rules/common/patterns.md}'s API
 * response format: total/page/limit alongside the payload).
 */
public record AuditEventPage(
        List<AuditEventView> events,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
