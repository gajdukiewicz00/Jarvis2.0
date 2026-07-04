package org.jarvis.apigateway.capability;

import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GatewayCapabilityService {

    private final RuntimeModeResolver runtimeModeResolver;
    private final PcControlWebSocketHandler pcControlWebSocketHandler;
    private final boolean visionSecurityEnabled;
    private final boolean visionSecurityLocalBridge;
    private final boolean memoryServiceEnabled;
    private final boolean llmServiceEnabled;
    private final boolean pcControlStubMode;
    private final boolean pcControlLocalBridge;

    public GatewayCapabilityService(RuntimeModeResolver runtimeModeResolver,
                                    PcControlWebSocketHandler pcControlWebSocketHandler,
                                    @Value("${services.vision-security.enabled:false}") boolean visionSecurityEnabled,
                                    @Value("${services.vision-security.local-bridge:${VISION_SECURITY_LOCAL_BRIDGE:false}}") boolean visionSecurityLocalBridge,
                                    @Value("${services.memory.enabled:false}") boolean memoryServiceEnabled,
                                    @Value("${services.llm.enabled:${JARVIS_LLM_ENABLED:false}}") boolean llmServiceEnabled,
                                    @Value("${services.pc-control.stub-mode:${PC_CONTROL_STUB_MODE:false}}") boolean pcControlStubMode,
                                    @Value("${services.pc-control.local-bridge:${PC_CONTROL_LOCAL_BRIDGE:false}}") boolean pcControlLocalBridge) {
        this.runtimeModeResolver = runtimeModeResolver;
        this.pcControlWebSocketHandler = pcControlWebSocketHandler;
        this.visionSecurityEnabled = visionSecurityEnabled;
        this.visionSecurityLocalBridge = visionSecurityLocalBridge;
        this.memoryServiceEnabled = memoryServiceEnabled;
        this.llmServiceEnabled = llmServiceEnabled;
        this.pcControlStubMode = pcControlStubMode;
        this.pcControlLocalBridge = pcControlLocalBridge;
    }

    public void requireVisionSecuritySupport(String capability) {
        RuntimeMode runtimeMode = runtimeModeResolver.currentMode();
        if (!visionSecurityEnabled) {
            throw new CapabilityUnavailableException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FEATURE_DISABLED",
                    "Vision security is disabled in this runtime",
                    "vision-security-service",
                    capability,
                    runtimeMode,
                    List.of(RuntimeMode.LOCAL.id(), RuntimeMode.DEV.id(), RuntimeMode.K8S.id()),
                    Map.of("serviceEnabled", false));
        }
        // K8s mode is normally blocked because CV is workstation-local. The
        // local-bridge flag is set by jarvis-launch.sh only after it has
        // started the host vision-security-service process AND patched the
        // selectorless Endpoints in infra/k8s/base/vision-security-service/
        // to the host IP. While that bridge is wired we can safely let
        // /api/v1/vision-security/** flow through to the workstation process.
        if (runtimeMode == RuntimeMode.K8S && !visionSecurityLocalBridge) {
            throw new CapabilityUnavailableException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UNSUPPORTED_RUNTIME_MODE",
                    "Vision security is only supported in a workstation-local runtime",
                    "vision-security-service",
                    capability,
                    runtimeMode,
                    List.of(RuntimeMode.LOCAL.id(), RuntimeMode.DEV.id()),
                    Map.of("serviceEnabled", true, "localBridge", false));
        }
    }

    public void requireMemorySupport(String capability) {
        RuntimeMode runtimeMode = runtimeModeResolver.currentMode();
        if (!memoryServiceEnabled) {
            throw new CapabilityUnavailableException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FEATURE_DISABLED",
                    "Memory tooling is disabled in this runtime",
                    "memory-service",
                    capability,
                    runtimeMode,
                    List.of(RuntimeMode.LOCAL.id(), RuntimeMode.DEV.id(), RuntimeMode.K8S.id()),
                    Map.of("serviceEnabled", false));
        }
    }

    public void requireLlmSupport(String capability) {
        RuntimeMode runtimeMode = runtimeModeResolver.currentMode();
        if (!llmServiceEnabled) {
            throw new CapabilityUnavailableException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FEATURE_DISABLED",
                    "LLM capabilities are disabled in this runtime",
                    "llm-service",
                    capability,
                    runtimeMode,
                    List.of(RuntimeMode.LOCAL.id(), RuntimeMode.DEV.id(), RuntimeMode.K8S.id()),
                    Map.of("serviceEnabled", false));
        }
    }

    public void requireDirectPcControlSupport(String capability) {
        RuntimeMode runtimeMode = runtimeModeResolver.currentMode();
        if ((pcControlStubMode || runtimeMode == RuntimeMode.K8S) && !pcControlLocalBridge) {
            throw new CapabilityUnavailableException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UNSUPPORTED_RUNTIME_MODE",
                    "Direct PC control REST routes require a workstation runtime with non-stub pc-control",
                    "pc-control",
                    capability,
                    runtimeMode,
                    List.of(RuntimeMode.LOCAL.id(), RuntimeMode.DEV.id()),
                    Map.of("stubMode", pcControlStubMode));
        }
    }

    public Map<String, Object> describeCapabilities() {
        RuntimeMode runtimeMode = runtimeModeResolver.currentMode();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "api-gateway");
        response.put("runtimeMode", runtimeMode.id());
        response.put("status", overallStatus(runtimeMode));
        response.put("routes", List.of(
                routeDescriptor("/auth/**", "security-service", true, "available", null),
                routeDescriptor("/api/v1/analytics/**", "analytics-service", true, "available", null),
                routeDescriptor("/api/v1/life/**", "life-tracker", true, "available", null),
                routeDescriptor("/api/v1/llm/**", "llm-service", false,
                        llmServiceEnabled ? "available" : "disabled",
                        llmServiceEnabled ? null : "JARVIS_LLM_ENABLED=false"),
                routeDescriptor("/api/v1/nlp/**", "nlp-service", true, "available", null),
                routeDescriptor("/api/v1/orchestrator/**", "orchestrator", true, "available", null),
                routeDescriptor("/api/v1/pc/**", "pc-control", false,
                        directPcControlStatus(runtimeMode),
                        directPcControlReason(runtimeMode)),
                routeDescriptor("/api/v1/planner/**", "planner-service", true, "available", null),
                routeDescriptor("/api/v1/smarthome/**", "smart-home-service", true, "available", null),
                routeDescriptor("/api/v1/tools/**", "planner-service/life-tracker/memory-service", true, toolsStatus(), toolsReason()),
                routeDescriptor("/api/v1/vision-security/**", "vision-security-service", false,
                        visionSecurityStatus(runtimeMode),
                        visionSecurityReason(runtimeMode)),
                routeDescriptor("/api/v1/voice/**", "voice-gateway", true, "available", null),
                routeDescriptor("/api/v1/status/report", "api-gateway", true, "available", null),
                websocketDescriptor("/ws/voice", "voice-gateway", "available", null, null),
                websocketDescriptor("/ws/pc-control", "desktop-clients", "session-dependent",
                        "Desktop availability depends on connected authenticated clients",
                        Map.of("connectedClients", pcControlWebSocketHandler.getConnectedClientsCount()))
        ));
        return response;
    }

    private String overallStatus(RuntimeMode runtimeMode) {
        if (!llmServiceEnabled || !memoryServiceEnabled) {
            return "degraded";
        }
        if (runtimeMode == RuntimeMode.K8S) {
            // K8S mode can be backend-ready while direct workstation control
            // remains route-level unsupported. Keep that boundary in the
            // /api/v1/pc/** descriptor instead of degrading the whole gateway.
            if (visionSecurityEnabled && !visionSecurityLocalBridge) {
                return "degraded";
            }
            return "ready";
        }
        if (pcControlStubMode) {
            return "degraded";
        }
        return "ready";
    }

    private String toolsStatus() {
        return memoryServiceEnabled ? "available" : "partially-degraded";
    }

    private String toolsReason() {
        return memoryServiceEnabled ? null : "Memory tool routes are disabled";
    }

    private String visionSecurityStatus(RuntimeMode runtimeMode) {
        if (!visionSecurityEnabled) {
            return "disabled";
        }
        if (runtimeMode == RuntimeMode.K8S) {
            return visionSecurityLocalBridge ? "available-via-local-bridge" : "unsupported-runtime";
        }
        return "available";
    }

    private String visionSecurityReason(RuntimeMode runtimeMode) {
        if (!visionSecurityEnabled) {
            return "services.vision-security.enabled=false";
        }
        if (runtimeMode == RuntimeMode.K8S) {
            return visionSecurityLocalBridge
                    ? "Routed via vision-security-service selectorless Service to the workstation host"
                    : "vision-security-service is local-only; set VISION_SECURITY_LOCAL_BRIDGE=true after wiring the host endpoints";
        }
        return null;
    }

    private String directPcControlStatus(RuntimeMode runtimeMode) {
        if (pcControlStubMode || runtimeMode == RuntimeMode.K8S) {
            return "unsupported-runtime";
        }
        return "available";
    }

    private String directPcControlReason(RuntimeMode runtimeMode) {
        if (pcControlStubMode) {
            return "pc-control is configured in stub mode";
        }
        if (runtimeMode == RuntimeMode.K8S) {
            return "direct workstation control is not supported in k8s";
        }
        return null;
    }

    private Map<String, Object> routeDescriptor(String route,
                                                String downstream,
                                                boolean mandatory,
                                                String status,
                                                String reason) {
        Map<String, Object> routeDescriptor = new LinkedHashMap<>();
        routeDescriptor.put("route", route);
        routeDescriptor.put("downstream", downstream);
        routeDescriptor.put("mandatory", mandatory);
        routeDescriptor.put("status", status);
        if (reason != null) {
            routeDescriptor.put("reason", reason);
        }
        return routeDescriptor;
    }

    private Map<String, Object> websocketDescriptor(String route,
                                                    String downstream,
                                                    String status,
                                                    String reason,
                                                    Map<String, Object> details) {
        Map<String, Object> descriptor = routeDescriptor(route, downstream, false, status, reason);
        descriptor.put("websocket", true);
        if (details != null && !details.isEmpty()) {
            descriptor.put("details", details);
        }
        return descriptor;
    }
}
