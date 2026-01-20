package org.jarvis.lifetracker.tooling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ToolRequestRepository extends JpaRepository<ToolRequest, Long> {
    Optional<ToolRequest> findByIdempotencyKey(String idempotencyKey);

    long deleteByCreatedAtBefore(Instant cutoff);
}
