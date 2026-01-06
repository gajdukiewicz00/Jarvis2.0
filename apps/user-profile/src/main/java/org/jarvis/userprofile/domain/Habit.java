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
public class Habit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String userId;
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    private String frequency; // daily, weekly, monthly
    private String timeOfDay; // morning, afternoon, evening, night
    private Boolean reminderEnabled;
    private Integer streakDays;
    private LocalDate lastCompletedDate;
    
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (streakDays == null) {
            streakDays = 0;
        }
        if (reminderEnabled == null) {
            reminderEnabled = false;
        }
    }
}
