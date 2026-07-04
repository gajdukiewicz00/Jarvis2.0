package org.jarvis.lifetracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jarvis.lifetracker.lifemap.TimeCategory;

import java.time.Instant;

/**
 * Phase 12 — durable activity timeline row (backs the life-map). Replaces the
 * volatile in-memory ring buffer so history survives restarts.
 */
@Entity
@Table(name = "life_activity_entries")
@Getter
@Setter
@NoArgsConstructor
public class LifeActivityEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false, unique = true, length = 64)
    private String entryId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds", nullable = false)
    private long durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TimeCategory category;

    @Column(name = "app_name", length = 255)
    private String appName;

    @Column(name = "window_title", length = 500)
    private String windowTitle;

    @Column(length = 50)
    private String source;
}
