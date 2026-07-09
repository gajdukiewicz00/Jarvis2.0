package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Reads the user's finance summary through the API Gateway's life-tracker proxy
 * ({@code /api/v1/life/finance/**}). Only api-gateway can reach life-tracker (NetworkPolicy),
 * so voice-gateway routes finance reads through the orchestrator, which is allowed to call the
 * gateway. {@link ServiceAuthFeignConfig} attaches the SVC_INTERNAL token; X-User-Id is
 * forwarded so life-tracker resolves the right user's expenses.
 */
@FeignClient(
        name = "api-gateway-finance",
        url = "${api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}",
        configuration = ServiceAuthFeignConfig.class)
public interface ApiGatewayFinanceClient {

    /** Month total expense + top categories. {@code month} is YYYY-MM (current month if null). */
    @GetMapping("/api/v1/life/finance/summary/month")
    Map<String, Object> getMonthSummary(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(value = "month", required = false) String month);

    /** Spending analysis over a window, grouped by category (used for today's spend). */
    @GetMapping("/api/v1/life/finance/analysis/spending")
    Map<String, Object> getSpending(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "groupBy", defaultValue = "category") String groupBy);
}
