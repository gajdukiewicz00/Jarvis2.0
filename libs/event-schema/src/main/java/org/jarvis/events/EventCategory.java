package org.jarvis.events;

/**
 * Phase 8 — top-level routing key for Jarvis events.
 *
 * <p>Each value maps 1:1 to a Kafka topic in {@link EventTopics}. The
 * audit projector consumes only {@link #AUDIT}; the others are intended
 * for analytics, life-tracker, the desktop live feed, etc.</p>
 */
public enum EventCategory {
    VOICE,
    DESKTOP_ACTIVITY,
    VISION,
    AUDIT,
    LIFE,
    MEMORY,
    ANALYTICS
}
