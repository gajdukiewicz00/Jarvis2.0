package org.jarvis.lifetracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "active_time_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_active_time_record_user", columnNames = "user_id")
)
@Data
public class ActiveTimeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(nullable = false, length = 200)
    private String activity;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;
}
