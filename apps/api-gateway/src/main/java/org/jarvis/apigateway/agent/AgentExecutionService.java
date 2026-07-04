package org.jarvis.apigateway.agent;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The missing half of "real agency": llm-service {@code orchestrate()} only
 * PLANS validated tool calls. This service runs the plan — it dispatches each
 * non-confirmation tool call to the corresponding backend tool endpoint
 * (planner / life-tracker / memory) with the user's identity and the
 * orchestrator-supplied idempotency key, so the assistant actually DOES things
 * instead of only claiming to.
 *
 * <p>Confirmation-required tool calls (e.g. calendar writes) are returned to the
 * caller as pending rather than executed — a human gate stays in the loop.</p>
 */
@Slf4j
@Service
public class AgentExecutionService {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    /** Canonical tool name -> backend tool endpoint path (matches the Tool*Controller routes). */
    private static final Map<String, String> TOOL_PATHS = Map.ofEntries(
            Map.entry("create_todo", "/api/v1/tools/todo/create"),
            Map.entry("update_todo", "/api/v1/tools/todo/update"),
            Map.entry("complete_todo", "/api/v1/tools/todo/complete"),
            Map.entry("list_todos", "/api/v1/tools/todo/list"),
            Map.entry("create_event", "/api/v1/tools/calendar/create"),
            Map.entry("move_event", "/api/v1/tools/calendar/move"),
            Map.entry("list_events", "/api/v1/tools/calendar/list"),
            Map.entry("find_free_slot", "/api/v1/tools/calendar/free-slot"),
            Map.entry("memory_search", "/api/v1/tools/memory/search"));

    private final LlmOrchestrateClient orchestrateClient;
    private final ServiceJwtProvider serviceJwtProvider;
    private final ToolPermissionPolicy permissionPolicy;
    private final SystemPanicState panicState;
    private final RestTemplate restTemplate;

    @Value("${services.planner.url}")
    private String plannerUrl;

    @Value("${services.life-tracker.url}")
    private String lifeTrackerUrl;

    @Value("${services.memory.url}")
    private String memoryUrl;

    public AgentExecutionService(LlmOrchestrateClient orchestrateClient,
                                 ServiceJwtProvider serviceJwtProvider,
                                 ToolPermissionPolicy permissionPolicy,
                                 SystemPanicState panicState,
                                 org.springframework.boot.web.client.RestTemplateBuilder builder) {
        this.orchestrateClient = orchestrateClient;
        this.serviceJwtProvider = serviceJwtProvider;
        this.permissionPolicy = permissionPolicy;
        this.panicState = panicState;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Map<String, Object> execute(String userId, String sessionId, String intent,
                                       Boolean includeMemory, String locale, Integer maxToolCalls,
                                       boolean dryRun) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();

        // Global panic kill-switch (defense-in-depth; PanicGuardFilter also blocks at the edge).
        if (panicState.isEngaged()) {
            log.warn("AGENT_EXECUTE refused — system panic engaged, userId={}", userId);
            result.put("error", "system_panic_engaged");
            result.put("executed", List.of());
            return result;
        }

        OrchestrateRequest req = new OrchestrateRequest(
                sessionId, userId, intent, Map.of(),
                includeMemory != null ? includeMemory : Boolean.FALSE,
                locale != null ? locale : "ru",
                maxToolCalls != null ? maxToolCalls : 4);

        OrchestrateResponse plan = orchestrateClient.orchestrate(req);

        List<Map<String, Object>> executed = new ArrayList<>();
        List<Map<String, Object>> proposed = new ArrayList<>();
        List<Map<String, Object>> pendingConfirmation = new ArrayList<>();

        List<OrchestrateResponse.PlannedToolCall> calls =
                plan.getToolCalls() == null ? List.of() : plan.getToolCalls();

        for (OrchestrateResponse.PlannedToolCall call : calls) {
            if (Boolean.TRUE.equals(call.getRequiresConfirmation())) {
                pendingConfirmation.add(Map.of("tool", call.getName(), "arguments", nullSafe(call.getArguments())));
                continue;
            }

            // Per-tool granular permission gate (EPIC 3).
            if (!permissionPolicy.isAllowed(call.getName())) {
                log.warn("AGENT_TOOL_DENIED tool={} missing={} userId={}",
                        call.getName(), permissionPolicy.missingFor(call.getName()), userId);
                Map<String, Object> denied = new java.util.LinkedHashMap<>();
                denied.put("tool", call.getName());
                denied.put("executed", false);
                denied.put("error", "permission_denied");
                denied.put("missingPermissions", permissionPolicy.missingFor(call.getName()).stream().map(Enum::name).toList());
                executed.add(denied);
                continue;
            }

            if (dryRun) {
                Map<String, Object> p = new java.util.LinkedHashMap<>();
                p.put("tool", call.getName());
                p.put("arguments", nullSafe(call.getArguments()));
                p.put("wouldExecute", true);
                proposed.add(p);
            } else {
                executed.add(dispatch(userId, call));
            }
        }

        result.put("explanation", plan.getExplanation());
        result.put("confidence", plan.getConfidence());
        result.put("dryRun", dryRun);
        result.put("executed", executed);
        if (dryRun) {
            result.put("proposed", proposed);
        }
        result.put("pendingConfirmation", pendingConfirmation);
        result.put("warnings", plan.getWarnings());
        return result;
    }

    private Map<String, Object> dispatch(String userId, OrchestrateResponse.PlannedToolCall call) {
        String path = TOOL_PATHS.get(call.getName());
        Map<String, Object> outcome = new java.util.LinkedHashMap<>();
        outcome.put("tool", call.getName());
        if (path == null) {
            outcome.put("executed", false);
            outcome.put("error", "unsupported_tool");
            return outcome;
        }

        String url = baseUrlFor(path) + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(USER_ID_HEADER, userId);
        if (call.getIdempotencyKey() != null) {
            headers.set(IDEMPOTENCY_HEADER, call.getIdempotencyKey());
        }
        headers.set(SERVICE_TOKEN_HEADER, serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL")));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(nullSafe(call.getArguments()), headers);
        try {
            ResponseEntity<Object> resp = restTemplate.postForEntity(url, entity, Object.class);
            outcome.put("executed", true);
            outcome.put("status", resp.getStatusCode().value());
            outcome.put("result", resp.getBody());
            log.info("AGENT_TOOL_EXECUTED tool={} status={} userId={}", call.getName(), resp.getStatusCode().value(), userId);
        } catch (RestClientResponseException e) {
            outcome.put("executed", false);
            outcome.put("status", e.getStatusCode().value());
            outcome.put("error", e.getResponseBodyAsString());
            log.warn("AGENT_TOOL_FAILED tool={} status={} body={}", call.getName(), e.getStatusCode().value(),
                    e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            outcome.put("executed", false);
            outcome.put("error", e.getMessage());
            log.warn("AGENT_TOOL_ERROR tool={} error={}", call.getName(), e.getMessage());
        }
        return outcome;
    }

    private String baseUrlFor(String path) {
        if (path.startsWith("/api/v1/tools/todo")) {
            return plannerUrl;
        }
        if (path.startsWith("/api/v1/tools/calendar") || path.startsWith("/api/v1/tools/finance")) {
            return lifeTrackerUrl;
        }
        return memoryUrl;
    }

    private Map<String, Object> nullSafe(Map<String, Object> args) {
        return args == null ? Map.of() : args;
    }
}
