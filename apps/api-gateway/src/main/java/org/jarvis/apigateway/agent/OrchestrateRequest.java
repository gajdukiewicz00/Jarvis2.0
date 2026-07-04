package org.jarvis.apigateway.agent;

import java.util.Map;

/** Request body sent to llm-service POST /api/v1/llm/orchestrate. */
public record OrchestrateRequest(
        String sessionId,
        String userId,
        String intent,
        Map<String, Object> context,
        Boolean includeMemory,
        String locale,
        Integer maxToolCalls) {
}
