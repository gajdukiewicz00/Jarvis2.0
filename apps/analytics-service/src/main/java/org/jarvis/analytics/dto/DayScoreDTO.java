package org.jarvis.analytics.dto;

import java.util.Map;

/** A 0..100 "how was the day" score with its component breakdown. */
public record DayScoreDTO(int score, String grade, Map<String, Object> components) {
}
