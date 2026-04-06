package org.jarvis.userprofile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPriorityDto {
    private Long id;
    private String userId;
    private String name;
    private Integer level;
    private String description;
    private LocalDateTime updatedAt;
}
