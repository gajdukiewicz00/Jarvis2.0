package org.jarvis.apigateway.status;

import org.jarvis.apigateway.capability.RuntimeMode;
import org.jarvis.apigateway.capability.RuntimeModeResolver;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusReportServiceTest {

    private static final String VOICE = "http://voice-gateway:8081";
    private static final String VISION = "http://vision-security-service:8094";
    private static final String LLM = "http://llm-service:8091";
    private static final String MEMORY = "http://memory-service:8093";
    private static final String PC = "http://pc-control:8084";
    private static final String ORCH = "http://orchestrator:8083";
    private static final String SEC = "http://security-service:8088";

    private final RuntimeModeResolver runtimeModeResolver = mock(RuntimeModeResolver.class);
    private final PcControlWebSocketHandler pcHandler = mock(PcControlWebSocketHandler.class);

    private StatusReportService service(HealthProbe probe, boolean vision, boolean llm,
                                        boolean memory, int desktopClients) {
        when(runtimeModeResolver.currentMode()).thenReturn(RuntimeMode.LOCAL);
        when(pcHandler.getConnectedClientsCount()).thenReturn(desktopClients);
        return new StatusReportService(probe, runtimeModeResolver, pcHandler,
                VOICE, VISION, LLM, MEMORY, PC, ORCH, SEC, vision, memory, llm);
    }

    /** Probe stub: any URL whose base is in {@code healthy} answers 2xx. */
    private HealthProbe probeFor(Set<String> healthy) {
        return healthy::contains;
    }

    private SubsystemStatus find(StatusReportService svc, String name) {
        return svc.subsystems().stream()
                .filter(s -> s.subsystem().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void allHealthyAndEnabledReportsOk() {
        StatusReportService svc = service(
                probeFor(Set.of(VOICE, VISION, LLM, MEMORY, PC, ORCH, SEC)),
                true, true, true, 1);

        assertThat(svc.subsystems())
                .allMatch(s -> s.status() == SubsystemHealth.OK);
        assertThat(svc.report().get("overall")).isEqualTo("OK");
    }

    @Test
    void disabledOptionalSubsystemsAreDegradedNotBroken() {
        StatusReportService svc = service(
                probeFor(Set.of(VOICE, PC, ORCH, SEC)),
                false, false, false, 1);

        assertThat(find(svc, "LLM").status()).isEqualTo(SubsystemHealth.DEGRADED);
        assertThat(find(svc, "LLM").detail()).contains("JARVIS_LLM_ENABLED=false");
        assertThat(find(svc, "Memory").status()).isEqualTo(SubsystemHealth.DEGRADED);
        assertThat(find(svc, "Vision").status()).isEqualTo(SubsystemHealth.DEGRADED);
        // No BROKEN subsystem -> overall is DEGRADED, not BROKEN.
        assertThat(svc.report().get("overall")).isEqualTo("DEGRADED");
    }

    @Test
    void unreachableCoreSubsystemIsBroken() {
        // Everything healthy except the orchestrator (Commands).
        Predicate<String> healthy = url -> !url.equals(ORCH);
        StatusReportService svc = service(healthy::test, true, true, true, 1);

        assertThat(find(svc, "Commands").status()).isEqualTo(SubsystemHealth.BROKEN);
        assertThat(svc.report().get("overall")).isEqualTo("BROKEN");
    }

    @Test
    void desktopReachableButNoClientIsDegraded() {
        StatusReportService svc = service(
                probeFor(Set.of(VOICE, VISION, LLM, MEMORY, PC, ORCH, SEC)),
                true, true, true, 0);

        assertThat(find(svc, "Desktop").status()).isEqualTo(SubsystemHealth.DEGRADED);
        assertThat(find(svc, "Desktop").detail()).contains("no desktop client");
    }

    @Test
    void reportPayloadHasAllSevenSubsystems() {
        StatusReportService svc = service(probeFor(Set.of()), true, true, true, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> subsystems = (Map<String, Object>) svc.report().get("subsystems");
        assertThat(subsystems.keySet()).containsExactlyInAnyOrder(
                "Voice", "Vision", "LLM", "Memory", "Desktop", "Commands", "Infra");
        assertThat(svc.report().get("service")).isEqualTo("api-gateway");
        assertThat(svc.report().get("runtimeMode")).isEqualTo("local");
    }
}
