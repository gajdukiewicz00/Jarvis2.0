package org.jarvis.planner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(nullable = false, length = 500)
    private String title;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private TaskCategory category = TaskCategory.PERSONAL;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private TaskPriority priority = TaskPriority.MEDIUM;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "deadline")
    private Instant dueDate;

    @Convert(converter = TaskTagsConverter.class)
    @Column(name = "tags")
    private List<String> tags = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskSource source = TaskSource.MANUAL;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "estimated_duration")
    private Integer estimatedDuration; // minutes
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(name = "completed_at")
    private Instant completedAt;

    // Recurring tasks (RRULE-lite): DAILY / WEEKLY / INTERVAL templates.
    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_rule", length = 20, nullable = false)
    private RecurrenceRule recurrenceRule = RecurrenceRule.NONE;

    @Column(name = "recurrence_interval_days")
    private Integer recurrenceIntervalDays;

    /** Pattern anchor: day-of-week for WEEKLY, start date for INTERVAL. */
    @Column(name = "recurrence_anchor_date")
    private LocalDate recurrenceAnchorDate;

    /** Set on a generated occurrence, pointing back at its recurring template task. */
    @Column(name = "recurrence_source_task_id")
    private Long recurrenceSourceTaskId;

    /** Last date an occurrence was generated for this recurring template (dedup guard). */
    @Column(name = "last_generated_date")
    private LocalDate lastGeneratedDate;
}
