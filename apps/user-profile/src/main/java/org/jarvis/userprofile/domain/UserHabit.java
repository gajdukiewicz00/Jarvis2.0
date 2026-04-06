package org.jarvis.userprofile.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_habits")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserHabit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    private String frequency; // e.g., "DAILY", "WEEKLY", "MON,WED,FRI"

    private String timeOfDay;

    private Boolean reminderEnabled;

    private Integer streakDays;

    private LocalDate lastCompletedDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (reminderEnabled == null) {
            reminderEnabled = false;
        }
        if (streakDays == null) {
            streakDays = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
