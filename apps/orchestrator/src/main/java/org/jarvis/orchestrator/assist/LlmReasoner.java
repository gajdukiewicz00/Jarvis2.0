package org.jarvis.orchestrator.assist;

import java.util.List;
import java.util.Map;

/** Local-LLM reasoning step for assist. Never calls a cloud model. */
public interface LlmReasoner {

    record Reasoning(boolean available, String answer, String actionType, String actionTarget, String error) {
        public static Reasoning unavailable(String e) { return new Reasoning(false, null, "NONE", "", e); }
        public static Reasoning of(String answer, String type, String target) {
            return new Reasoning(true, answer, type == null || type.isBlank() ? "NONE" : type,
                    target == null ? "" : target, null);
        }
    }

    Reasoning reason(String command, Map<String, Object> screenContext, List<String> memory,
                     String correlationId, String userId);
}
