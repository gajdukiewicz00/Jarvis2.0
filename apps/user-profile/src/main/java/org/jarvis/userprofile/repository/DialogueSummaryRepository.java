package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.model.DialogueSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface DialogueSummaryRepository extends JpaRepository<DialogueSummary, Long> {
    
    List<DialogueSummary> findByUserIdOrderByPeriodStartDesc(String userId);
    
    @Query("SELECT ds FROM DialogueSummary ds WHERE ds.userId = :userId " +
           "AND ds.periodStart >= :since ORDER BY ds.periodStart DESC")
    List<DialogueSummary> findRecentSummaries(String userId, Instant since);
    
    List<DialogueSummary> findBySessionId(String sessionId);
}
