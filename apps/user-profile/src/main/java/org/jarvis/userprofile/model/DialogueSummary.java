package org.jarvis.userprofile.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "dialogue_summaries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DialogueSummary {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "session_id")
    private String sessionId;
    
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;
    
    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;
    
    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;
    
    @Column(name = "important_facts", columnDefinition = "JSONB")
    private String importantFacts; // JSON array of facts
    
    @Column(columnDefinition = "VARCHAR(255)[]")
    private String[] tags;
    
    @Column(name = "message_count")
    private Integer messageCount;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
