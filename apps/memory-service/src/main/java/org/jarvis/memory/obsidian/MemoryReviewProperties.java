package org.jarvis.memory.obsidian;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Roadmap P1 #9 — "memory review / pending" queue thresholds
 * ({@code jarvis.memory.review.*}).
 *
 * <p>A note is considered "pending review" when it is ACTIVE and its
 * confidence is missing or below {@link #pendingConfidenceThreshold} —
 * i.e. Jarvis wrote it down but is not sure enough that it should stick
 * around unreviewed.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.memory.review")
public class MemoryReviewProperties {

    private BigDecimal pendingConfidenceThreshold = new BigDecimal("0.50");
}
