package org.jarvis.userprofile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserHabitDto {
    private Long id;
    private String userId;
    private String name;
    private String description;
    private String frequency;
    private String timeOfDay;
    private Boolean reminderEnabled;
    private Integer streakDays;
    private LocalDate lastCompletedDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
