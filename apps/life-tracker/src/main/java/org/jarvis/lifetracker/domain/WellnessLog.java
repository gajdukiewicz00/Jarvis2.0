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

import java.time.Instant;
import java.time.LocalDate;

/** A single wellness data point (habit / weight / mood / steps / workout / note). */
@Entity
@Table(name = "wellness_log")
@Getter
@Setter
@NoArgsConstructor
public class WellnessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WellnessType type;

    @Column(name = "numeric_value")
    private Double numericValue;

    @Column(name = "text_value", length = 1000)
    private String textValue;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    @Column(nullable = false)
    private LocalDate day;
}
