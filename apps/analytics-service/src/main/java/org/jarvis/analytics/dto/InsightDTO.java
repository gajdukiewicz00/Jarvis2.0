package org.jarvis.analytics.dto;

/** A single derived insight (anomaly / recommendation / observation). */
public record InsightDTO(String code, String title, String detail, String severity) {
}
