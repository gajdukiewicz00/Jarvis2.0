package org.jarvis.memory.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Phase 8 — query API for the desktop panel.
 *
 * <ul>
 *   <li>{@code GET /api/v1/audit/events?since=...&eventType=...&agentId=...&userId=...&limit=...}
 *       — recent activity, default limit 50, max 500.</li>
 *   <li>{@code GET /api/v1/audit/events/{eventId}} — single row by id.</li>
 * </ul>
 *
 * <p>The api-gateway proxies these for the desktop panel; auth is enforced
 * at the edge.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository repository;

    @GetMapping("/events")
    public List<AuditEventEntity> recent(
            @RequestParam(required = false) Instant since,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return repository.search(since, eventType, agentId, userId, PageRequest.of(0, safeLimit));
    }

    @GetMapping("/events/{eventId}")
    public AuditEventEntity get(@PathVariable String eventId) {
        return repository.findById(eventId).orElse(null);
    }
}
