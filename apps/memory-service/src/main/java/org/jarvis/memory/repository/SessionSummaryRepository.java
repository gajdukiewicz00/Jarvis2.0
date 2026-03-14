package org.jarvis.memory.repository;

import org.jarvis.memory.entity.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionSummaryRepository extends JpaRepository<SessionSummary, UUID> {

    Optional<SessionSummary> findBySessionId(String sessionId);

    Optional<SessionSummary> findBySessionIdAndUserId(String sessionId, String userId);

    List<SessionSummary> findByUserIdOrderByUpdatedAtDesc(String userId);

    boolean existsBySessionId(String sessionId);
}


