package org.jarvis.userprofile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserGoalDto {
    private Long id;
    private String userId;
    private String title;
    private String description;
    private String category;
    private BigDecimal targetValue;
    private BigDecimal currentValue;
    private LocalDate targetDate;
    private String status;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
