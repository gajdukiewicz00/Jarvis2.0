package org.jarvis.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Subset of life-tracker's WellnessLog needed by analytics. The authoritative
 * sleep source (health-entry / phone Health Connect sync) writes SLEEP wellness
 * entries whose {@code numericValue} is hours slept for {@code day}. HABIT
 * entries use {@code numericValue} as a 1/0 done flag and {@code textValue} as
 * the habit name (used by habit-streak analytics).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WellnessLogDTO {
    private String type;
    private Double numericValue;
    private LocalDate day;
    private String textValue;

    /** Legacy 3-arg constructor kept for existing callers that predate {@link #textValue}. */
    public WellnessLogDTO(String type, Double numericValue, LocalDate day) {
        this(type, numericValue, day, null);
    }

    public WellnessLogDTO(String type, Double numericValue, LocalDate day, String textValue) {
        this.type = type;
        this.numericValue = numericValue;
        this.day = day;
        this.textValue = textValue;
    }
}
