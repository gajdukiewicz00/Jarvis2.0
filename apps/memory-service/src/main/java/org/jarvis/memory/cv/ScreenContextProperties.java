package org.jarvis.memory.cv;

import lombok.Getter;
import lombok.Setter;
import org.jarvis.events.EventTopics;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the CV screen-context memory consumer
 * ({@code jarvis.memory.cv.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "jarvis.memory.cv")
public class ScreenContextProperties {

    /** Master switch for the screen-context Kafka consumer. */
    private boolean enabled = true;

    /** Topic to consume. Shared constant with the producer. */
    private String topic = EventTopics.CV_SCREEN_CONTEXT;

    /** Consumer group id (separate from the audit projector group). */
    private String consumerGroup = "cv-screen-context-projector";

    /**
     * Persist the raw screenshot bytes when the file referenced by the event
     * is readable by this process. In a clustered deployment the consumer pod
     * usually cannot read the producer host's file, so bytes stay null and
     * only the path is kept. Default true for the local same-host runtime.
     */
    private boolean storeRawScreenshot = true;

    /** Hard cap on stored screenshot size (bytes). Larger files store path only. */
    private long maxScreenshotBytes = 8L * 1024 * 1024;

    /**
     * Embed the OCR text into pgvector for later semantic recall. Requires a
     * reachable local embedding-service; failures degrade gracefully (the row
     * is still persisted with a null embedding).
     */
    private boolean embed = true;
}
