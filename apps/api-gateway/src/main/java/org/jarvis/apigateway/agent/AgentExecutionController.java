package org.jarvis.apigateway.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Real-agency entry point: turns a natural-language intent into ACTUAL backend
 * actions. Plans tool calls via llm-service, then executes the non-confirmation
 * ones against the tool endpoints. This closes the gap where the assistant
 * planned actions but never performed them (the "confabulation" symptom).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentExecutionController {

    private final AgentExecutionService agentExecutionService;

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            Authentication authentication,
            @RequestBody ExecuteRequest body) {

        String userId = authentication != null ? authentication.getName() : null;
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }
        if (body == null || body.intent() == null || body.intent().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "intent is required"));
        }

        String sessionId = body.sessionId() != null && !body.sessionId().isBlank()
                ? body.sessionId()
                : "agent-" + userId;
        log.info("AGENT_EXECUTE userId={} intent='{}'", userId, body.intent());

        boolean dryRun = body.dryRun() != null && body.dryRun();
        Map<String, Object> result = agentExecutionService.execute(
                userId, sessionId, body.intent(), body.includeMemory(), body.locale(), body.maxToolCalls(), dryRun);
        return ResponseEntity.ok(result);
    }

    public record ExecuteRequest(
            String sessionId,
            String intent,
            Boolean includeMemory,
            String locale,
            Integer maxToolCalls,
            Boolean dryRun) {
    }
}
