package org.jarvis.lifetracker.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "calendar_event")
@Data
public class CalendarEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(length = 255)
    private String location;

    @Column(name = "recurrence_rule", length = 255)
    private String recurrenceRule;

    @Column(name = "recurrence_until")
    private LocalDateTime recurrenceUntil;

    @Column(length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EntrySource source = EntrySource.MANUAL;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
