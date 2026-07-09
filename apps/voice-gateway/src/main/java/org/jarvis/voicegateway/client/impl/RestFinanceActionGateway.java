package org.jarvis.voicegateway.client.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.FinanceActionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Deterministic voice bridge to finance (life-tracker). Answers "что у нас с финансами" with a
 * REAL spoken summary instead of a generic LLM refusal.
 *
 * <p>voice-gateway is NetworkPolicy-blocked from life-tracker and api-gateway; only the
 * orchestrator is reachable. So this calls the orchestrator finance passthrough
 * ({@code /internal/finance/summary}) → api-gateway life-tracker proxy → life-tracker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestFinanceActionGateway implements FinanceActionGateway {

    @Value("${jarvis.finance.dispatch-url:${jarvis.orchestrator.url:http://orchestrator:8083}}")
    private String financeDispatchUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Override
    public FinanceResult summarize(String userId, String lang, String action) {
        boolean ru = lang == null || lang.startsWith("ru");
        try {
            Map<String, Object> data = fetchSummary(userId);
            String summary = FinanceVoiceSummary.build(
                    ru,
                    data.get("monthExpense"),
                    data.get("todayExpense"),
                    data.get("currency") != null ? String.valueOf(data.get("currency")) : null,
                    data.get("topCategories"));
            log.info("💰 Finance voice summary: userId={}, action={}, month={}, today={}",
                    userId, action, data.get("monthExpense"), data.get("todayExpense"));
            return new FinanceResult(true, summary, null);
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            log.warn("💰 Finance endpoint returned {}: userId={}, action={}", code, userId, action);
            return new FinanceResult(false, null, "FINANCE_ENDPOINT_HTTP_" + code
                    + ": finance endpoint returned " + code);
        } catch (RuntimeException e) {
            log.warn("💰 Finance voice summary unavailable: userId={}, action={}, error={}",
                    userId, action, e.getMessage());
            return new FinanceResult(false, null, "FINANCE_UNAVAILABLE: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSummary(String userId) {
        Map<String, Object> body = restClientBuilder.build()
                .get()
                .uri(financeDispatchUrl + "/internal/finance/summary")
                .header("X-Service-Token", serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")))
                .header("X-User-Id", userId)
                .retrieve()
                .body(Map.class);
        return body != null ? body : Map.of();
    }
}
