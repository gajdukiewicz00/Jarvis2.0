package org.jarvis.apigateway.agent;

import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Enforcement tests for the agent executor: A3 (permission gate), A4 (global
 * panic), A6 (dry-run). Verifies the RestTemplate is or is NOT invoked.
 */
class AgentExecutionServiceTest {

    private LlmOrchestrateClient orchestrateClient;
    private ServiceJwtProvider serviceJwtProvider;
    private SystemPanicState panicState;
    private RestTemplate restTemplate;
    private AgentExecutionService service;

    @BeforeEach
    void setUp() {
        orchestrateClient = mock(LlmOrchestrateClient.class);
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken(anyString(), any())).thenReturn("svc-token");
        panicState = new SystemPanicState();
        restTemplate = mock(RestTemplate.class);

        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);

        // default policy: PLANNER granted, FINANCE denied
        service = new AgentExecutionService(orchestrateClient, serviceJwtProvider,
                new ToolPermissionPolicy(""), panicState, builder);
    }

    private OrchestrateResponse planWith(String toolName) {
        OrchestrateResponse.PlannedToolCall call = new OrchestrateResponse.PlannedToolCall();
        call.setName(toolName);
        call.setArguments(Map.of("title", "x"));
        call.setRequiresConfirmation(false);
        call.setIdempotencyKey("idem-1");
        OrchestrateResponse plan = new OrchestrateResponse();
        plan.setExplanation("plan");
        plan.setConfidence(0.9);
        plan.setToolCalls(List.of(call));
        return plan;
    }

    @Test
    void panicEngagedBlocksExecutionEntirely() {
        panicState.engage("test", "drill", 1L);

        Map<String, Object> result = service.execute("2", "s", "добавь задачу", false, "ru", 2, false);

        assertThat(result).containsEntry("error", "system_panic_engaged");
        verifyNoInteractions(orchestrateClient);
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dryRunProposesWithoutDispatching() {
        when(orchestrateClient.orchestrate(any())).thenReturn(planWith("create_todo"));

        Map<String, Object> result = service.execute("2", "s", "добавь задачу", false, "ru", 2, true);

        assertThat(result).containsEntry("dryRun", true);
        assertThat((List<?>) result.get("executed")).isEmpty();
        assertThat((List<Map<String, Object>>) result.get("proposed"))
                .anySatisfy(p -> assertThat(p).containsEntry("tool", "create_todo"));
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deniedPermissionSkipsDispatch() {
        when(orchestrateClient.orchestrate(any())).thenReturn(planWith("finance_summary"));

        Map<String, Object> result = service.execute("2", "s", "покажи финансы", false, "ru", 2, false);

        List<Map<String, Object>> executed = (List<Map<String, Object>>) result.get("executed");
        assertThat(executed).hasSize(1);
        assertThat(executed.get(0)).containsEntry("error", "permission_denied");
        assertThat((List<String>) executed.get(0).get("missingPermissions")).contains("FINANCE_ACCESS");
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void grantedToolIsDispatched() {
        when(orchestrateClient.orchestrate(any())).thenReturn(planWith("create_todo"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", 1)));

        Map<String, Object> result = service.execute("2", "s", "добавь задачу", false, "ru", 2, false);

        assertThat(result).containsEntry("confidence", 0.9);
        List<Map<String, Object>> executed = (List<Map<String, Object>>) result.get("executed");
        assertThat(executed).hasSize(1);
        assertThat(executed.get(0)).containsEntry("executed", true);
        verify(restTemplate).postForEntity(anyString(), any(), eq(Object.class));
    }
}
