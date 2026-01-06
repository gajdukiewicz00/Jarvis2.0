package org.jarvis.memory.repository;

import org.jarvis.memory.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    /**
     * Find messages by user and session
     */
    List<ConversationMessage> findByUserIdAndSessionIdOrderByCreatedAtAsc(String userId, String sessionId);

    /**
     * Find recent messages by user
     */
    @Query("SELECT m FROM ConversationMessage m WHERE m.userId = :userId ORDER BY m.createdAt DESC LIMIT :limit")
    List<ConversationMessage> findRecentByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * Find messages by session
     */
    List<ConversationMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * Count messages in session
     */
    long countBySessionId(String sessionId);

    /**
     * Find messages after a certain date
     */
    List<ConversationMessage> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(
            String userId, OffsetDateTime after);

    /**
     * Delete old messages (for cleanup)
     */
    void deleteByCreatedAtBefore(OffsetDateTime before);
}



