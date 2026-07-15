package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Deterministic voice bridge to the planner. Reads the user's focus/day and turns it into a
 * short spoken summary so "какие планы на день" answers with REAL planner data instead of
 * falling through to generic LLM chat.
 *
 * <p>voice-gateway is NetworkPolicy-blocked from reaching planner-service (and api-gateway)
 * directly — only the orchestrator is reachable. So this calls the orchestrator's planner
 * passthrough ({@code /internal/planner/focus|daily}), which forwards to the api-gateway
 * planner proxy → planner-service (the same reachable path the desktop Planner uses).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestPlannerActionGateway implements PlannerActionGateway {

    @Value("${jarvis.planner.dispatch-url:${jarvis.orchestrator.url:http://orchestrator:8083}}")
    private String plannerDispatchUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Override
    public PlannerResult summarizeDay(String userId, String lang, String action) {
        boolean ru = lang == null || lang.startsWith("ru");
        try {
            Map<String, Object> focus = fetchFocus(userId);
            int openTasks = asInt(focus.get("openTasks"), -1);
            if (openTasks < 0) {
                openTasks = countDailyTasks(userId);
            }
            String title = asText(focus.get("title"));
            String summary = PlannerVoiceSummary.build(ru, openTasks, title, asText(focus.get("message")));
            log.info("🗓️ Planner voice summary: userId={}, action={}, openTasks={}, hasTitle={}",
                    userId, action, openTasks, title != null && !title.isBlank());
            return new PlannerResult(true, summary, null);
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            log.warn("🗓️ Planner endpoint returned {}: userId={}, action={}", code, userId, action);
            return new PlannerResult(false, null, "PLANNER_ENDPOINT_HTTP_" + code
                    + ": planner endpoint returned " + code);
        } catch (RuntimeException e) {
            log.warn("🗓️ Planner voice summary unavailable: userId={}, action={}, error={}",
                    userId, action, e.getMessage());
            return new PlannerResult(false, null, "PLANNER_UNAVAILABLE: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchFocus(String userId) {
        Map<String, Object> body = restClientBuilder.build()
                .get()
                .uri(plannerDispatchUrl + "/internal/planner/focus")
                .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                .header("X-User-Id", userId)
                .retrieve()
                .body(Map.class);
        return body != null ? body : Map.of();
    }

    /** Fallback count when /focus omits openTasks: count today's tasks from /daily. */
    @SuppressWarnings("unchecked")
    private int countDailyTasks(String userId) {
        try {
            Map<String, Object> daily = restClientBuilder.build()
                    .get()
                    .uri(plannerDispatchUrl + "/internal/planner/daily")
                    .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(Map.class);
            if (daily == null) {
                return 0;
            }
            Object tasks = daily.get("tasksForDay");
            return tasks instanceof List<?> list ? list.size() : 0;
        } catch (RuntimeException e) {
            log.debug("Planner /daily fallback count failed: {}", e.getMessage());
            return 0;
        }
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String asText(Object value) {
        return value != null ? value.toString() : null;
    }
}
