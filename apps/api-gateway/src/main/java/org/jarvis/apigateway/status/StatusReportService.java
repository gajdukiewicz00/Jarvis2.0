package org.jarvis.apigateway.status;

import org.jarvis.apigateway.capability.RuntimeModeResolver;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the cross-subsystem status report served at {@code /api/v1/status/report}.
 *
 * <p>This is the implementation behind the "Jarvis status report" end-to-end
 * scenario: it rolls up the health of every major subsystem (Voice, Vision, LLM,
 * Memory, Desktop, Commands, Infra) into a single {@link SubsystemHealth} value
 * each, plus an overall rollup.</p>
 *
 * <p>Feature-flag semantics are honoured: a subsystem that is intentionally
 * disabled (e.g. {@code JARVIS_LLM_ENABLED=false}) reports {@code DEGRADED} with a
 * reason rather than {@code BROKEN}, so a minimal local profile does not look
 * like an outage. A subsystem that is expected to be up but is unreachable
 * reports {@code BROKEN}.</p>
 */
@Service
public class StatusReportService {

    private final HealthProbe healthProbe;
    private final RuntimeModeResolver runtimeModeResolver;
    private final PcControlWebSocketHandler pcControlWebSocketHandler;

    private final String voiceUrl;
    private final String visionUrl;
    private final String llmUrl;
    private final String memoryUrl;
    private final String pcControlUrl;
    private final String orchestratorUrl;
    private final String securityUrl;

    private final boolean visionEnabled;
    private final boolean memoryEnabled;
    private final boolean llmEnabled;

    public StatusReportService(
            HealthProbe healthProbe,
            RuntimeModeResolver runtimeModeResolver,
            PcControlWebSocketHandler pcControlWebSocketHandler,
            @Value("${services.voice-gateway.url:http://voice-gateway:8081}") String voiceUrl,
            @Value("${services.vision-security.url:http://vision-security-service:8094}") String visionUrl,
            @Value("${services.llm.url:http://llm-service:8091}") String llmUrl,
            @Value("${services.memory.url:http://memory-service:8093}") String memoryUrl,
            @Value("${services.pc-control.url:http://pc-control:8084}") String pcControlUrl,
            @Value("${services.orchestrator.url:http://orchestrator:8083}") String orchestratorUrl,
            @Value("${services.security.url:http://security-service:8088}") String securityUrl,
            @Value("${services.vision-security.enabled:false}") boolean visionEnabled,
            @Value("${services.memory.enabled:false}") boolean memoryEnabled,
            @Value("${services.llm.enabled:${JARVIS_LLM_ENABLED:false}}") boolean llmEnabled) {
        this.healthProbe = healthProbe;
        this.runtimeModeResolver = runtimeModeResolver;
        this.pcControlWebSocketHandler = pcControlWebSocketHandler;
        this.voiceUrl = voiceUrl;
        this.visionUrl = visionUrl;
        this.llmUrl = llmUrl;
        this.memoryUrl = memoryUrl;
        this.pcControlUrl = pcControlUrl;
        this.orchestratorUrl = orchestratorUrl;
        this.securityUrl = securityUrl;
        this.visionEnabled = visionEnabled;
        this.memoryEnabled = memoryEnabled;
        this.llmEnabled = llmEnabled;
    }

    /** Probe every subsystem and return the per-subsystem rollups. */
    public List<SubsystemStatus> subsystems() {
        List<SubsystemStatus> result = new ArrayList<>();
        result.add(probed("Voice", voiceUrl, "voice-gateway"));
        result.add(optionalSubsystem("Vision", visionEnabled, visionUrl,
                "vision-security-service", "VISION_SECURITY_ENABLED=false"));
        result.add(optionalSubsystem("LLM", llmEnabled, llmUrl,
                "llm-service", "JARVIS_LLM_ENABLED=false"));
        result.add(optionalSubsystem("Memory", memoryEnabled, memoryUrl,
                "memory-service", "MEMORY_SERVICE_ENABLED=false"));
        result.add(desktopStatus());
        result.add(probed("Commands", orchestratorUrl, "orchestrator"));
        result.add(probed("Infra", securityUrl, "security-service (auth + Postgres)"));
        return result;
    }

    /** Build the full {@code /status/report} payload. */
    public Map<String, Object> report() {
        List<SubsystemStatus> subsystems = subsystems();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "api-gateway");
        response.put("runtimeMode", runtimeModeResolver.currentMode().id());
        response.put("overall", overall(subsystems).name());
        Map<String, Object> bySubsystem = new LinkedHashMap<>();
        for (SubsystemStatus s : subsystems) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", s.status().name());
            entry.put("detail", s.detail());
            bySubsystem.put(s.subsystem(), entry);
        }
        response.put("subsystems", bySubsystem);
        response.put("timestamp", Instant.now().toString());
        return response;
    }

    private SubsystemStatus probed(String name, String url, String target) {
        boolean healthy = healthProbe.isHealthy(url);
        return new SubsystemStatus(name,
                healthy ? SubsystemHealth.OK : SubsystemHealth.BROKEN,
                healthy ? target + " reachable at " + url
                        : target + " unreachable at " + url);
    }

    private SubsystemStatus optionalSubsystem(String name, boolean enabled, String url,
                                              String target, String disabledFlag) {
        if (!enabled) {
            return new SubsystemStatus(name, SubsystemHealth.DEGRADED,
                    target + " disabled (" + disabledFlag + ")");
        }
        return probed(name, url, target);
    }

    private SubsystemStatus desktopStatus() {
        int clients = pcControlWebSocketHandler.getConnectedClientsCount();
        boolean controlHealthy = healthProbe.isHealthy(pcControlUrl);
        if (!controlHealthy) {
            return new SubsystemStatus("Desktop", SubsystemHealth.BROKEN,
                    "pc-control unreachable at " + pcControlUrl);
        }
        if (clients <= 0) {
            return new SubsystemStatus("Desktop", SubsystemHealth.DEGRADED,
                    "pc-control reachable but no desktop client connected");
        }
        return new SubsystemStatus("Desktop", SubsystemHealth.OK,
                clients + " desktop client(s) connected");
    }

    /** BROKEN wins over DEGRADED wins over OK. */
    private SubsystemHealth overall(List<SubsystemStatus> subsystems) {
        boolean anyBroken = subsystems.stream()
                .anyMatch(s -> s.status() == SubsystemHealth.BROKEN);
        if (anyBroken) {
            return SubsystemHealth.BROKEN;
        }
        boolean anyDegraded = subsystems.stream()
                .anyMatch(s -> s.status() == SubsystemHealth.DEGRADED);
        return anyDegraded ? SubsystemHealth.DEGRADED : SubsystemHealth.OK;
    }
}
