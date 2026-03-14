package org.jarvis.planner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;

@Entity
@Table(name = "recommendations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Recommendation {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation_type", length = 100, nullable = false)
    private RecommendationType recommendationType;
    
    @Column(length = 500)
    private String title;
    
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private TaskPriority priority = TaskPriority.LOW;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private RecommendationStatus status = RecommendationStatus.PENDING;
    
    @Column(columnDefinition = "JSONB")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String data; // Additional context as JSON
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @Column(name = "expires_at")
    private Instant expiresAt;
    
    @Column(name = "responded_at")
    private Instant respondedAt;
}
