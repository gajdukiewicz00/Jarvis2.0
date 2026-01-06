package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.domain.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
    List<ConversationMessage> findBySessionIdOrderByTimestampAsc(String sessionId);
    List<ConversationMessage> findByUserIdAndTimestampAfterOrderByTimestampDesc(String userId, LocalDateTime after);
    List<ConversationMessage> findTop10ByUserIdOrderByTimestampDesc(String userId);
}
