package org.jarvis.events;

/**
 * Phase 8 — Kafka topic names for the event backbone.
 *
 * <p>SPEC-1 § "Messaging Split" lists exactly seven topics. Names are
 * centralised here so producers and the audit projector cannot drift.</p>
 */
public final class EventTopics {

    private EventTopics() {}

    public static final String VOICE             = "jarvis.voice.events";
    public static final String DESKTOP_ACTIVITY  = "jarvis.desktop.activity.events";
    public static final String VISION            = "jarvis.vision.events";
    public static final String AUDIT             = "jarvis.audit.events";
    public static final String LIFE              = "jarvis.life.events";
    public static final String MEMORY            = "jarvis.memory.events";
    public static final String ANALYTICS         = "jarvis.analytics.events";

    /**
     * Extension topic (not one of the SPEC-1 seven): wide-view screen-context
     * observations produced by the {@code vision-security-service} CV slice and
     * consumed by {@code memory-service}. Centralised here so producer and
     * consumer share one constant. Deliberately excluded from {@link #all()}
     * and {@link #forCategory} which enumerate only the core seven.
     */
    public static final String CV_SCREEN_CONTEXT = "jarvis.cv.screen_context.created";

    public static String forCategory(EventCategory category) {
        return switch (category) {
            case VOICE             -> VOICE;
            case DESKTOP_ACTIVITY  -> DESKTOP_ACTIVITY;
            case VISION            -> VISION;
            case AUDIT             -> AUDIT;
            case LIFE              -> LIFE;
            case MEMORY            -> MEMORY;
            case ANALYTICS         -> ANALYTICS;
        };
    }

    public static String[] all() {
        return new String[] {
                VOICE, DESKTOP_ACTIVITY, VISION, AUDIT, LIFE, MEMORY, ANALYTICS
        };
    }
}
