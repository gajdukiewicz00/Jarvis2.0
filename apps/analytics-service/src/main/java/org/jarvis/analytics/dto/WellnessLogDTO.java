package org.jarvis.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Subset of life-tracker's WellnessLog needed by analytics. The authoritative
 * sleep source (health-entry / phone Health Connect sync) writes SLEEP wellness
 * entries whose {@code numericValue} is hours slept for {@code day}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WellnessLogDTO {
    private String type;
    private Double numericValue;
    private LocalDate day;
}
