package org.jarvis.planner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyPlan {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;
    
    @Column(name = "plan_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String planJson;
    
    @CreationTimestamp
    @Column(name = "generated_at", updatable = false)
    private Instant generatedAt;
    
    private Boolean confirmed = false;
}
