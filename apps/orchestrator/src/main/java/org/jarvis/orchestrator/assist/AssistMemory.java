package org.jarvis.orchestrator.assist;

import java.util.List;

/** Memory read/write for assist. Degrades gracefully when memory-service or
 *  embeddings are unavailable (returns empty / "skipped"), never throwing. */
public interface AssistMemory {
    List<String> readRecent(String userId, String query, String correlationId);
    String write(String userId, String command, String answer, String screenSummary,
                 String actionResult, String correlationId);
}
