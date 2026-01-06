package org.jarvis.planner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "reminders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Reminder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;
    
    @Column(name = "reminder_time", nullable = false)
    private Instant reminderTime;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", length = 50)
    private ReminderType reminderType = ReminderType.ONCE;
    
    @Column(name = "recurring_pattern")
    private String recurringPattern; // "MON,WED,FRI" or "DAILY"
    
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ReminderStatus status = ReminderStatus.ACTIVE;
    
    @Column(name = "linked_task_id")
    private Long linkedTaskId;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @Column(name = "triggered_at")
    private Instant triggeredAt;
}
