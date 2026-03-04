package org.jarvis.userprofile.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_priorities")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPriority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(name = "category", nullable = false)
    private String name;

    @Column(name = "priority_level", nullable = false)
    private Integer level; // 1 (High) to 5 (Low), for example

    @Column(name = "notes", length = 1000)
    private String description;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (level == null) {
            level = 3;
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
