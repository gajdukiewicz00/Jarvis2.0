package org.jarvis.orchestrator.assist;

import java.util.List;

/** Memory read/write for assist. Degrades gracefully when memory-service or
 *  embeddings are unavailable (returns empty / "skipped"), never throwing. */
public interface AssistMemory {
    ReadOutcome readRecent(String userId, String query, String correlationId);
    String write(String userId, String command, String answer, String screenSummary,
                 String actionResult, String correlationId);

    /**
     * Result of a memory read attempt. {@code degraded} is {@code true} when
     * the underlying memory-service call failed or was skipped because its
     * circuit breaker is open, so callers can surface a partial/degraded
     * response instead of silently treating "no memories" as normal.
     */
    record ReadOutcome(List<String> items, boolean degraded) {
        public static ReadOutcome ok(List<String> items) {
            return new ReadOutcome(items, false);
        }

        public static ReadOutcome unavailable() {
            return new ReadOutcome(List.of(), true);
        }
    }
}
