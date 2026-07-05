package org.jarvis.planner.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Persisted per-user {@link PlanMode} selection — one row per user. */
@Entity
@Table(name = "user_plan_modes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPlanMode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 255)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_mode", length = 30, nullable = false)
    private PlanMode planMode = PlanMode.NORMAL;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
