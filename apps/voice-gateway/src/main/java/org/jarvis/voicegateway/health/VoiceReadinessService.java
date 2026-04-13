package org.jarvis.voicegateway.health;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.audio.CanonicalWavAudio;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceReadinessService {

    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final byte[] STT_SELF_TEST_PCM = new byte[3200];

    private final SttService sttService;
    private final TtsService ttsService;
    private final VoiceAssetLoader voiceAssetLoader;
    private final WavResponseRegistry wavResponseRegistry;
    private final RestClient.Builder restClientBuilder;
    private final ServiceJwtProvider serviceJwtProvider;

    @Value("${jarvis.vosk.default-language:ru-RU}")
    private String defaultLanguage;

    @Value("${jarvis.orchestrator.url:http://orchestrator:8083}")
    private String orchestratorUrl;

    @Value("${jarvis.api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}")
    private String apiGatewayUrl;

    @Value("${spring.application.name:voice-gateway}")
    private String serviceName;

    @Value("${server.port:${JARVIS_VOICE_GATEWAY_PORT:8081}}")
    private int serverPort;

    @Value("${jarvis.voice.readiness.cache-ttl:PT15S}")
    private Duration cacheTtl;

    private volatile Snapshot cachedSnapshot;
    private volatile Instant cachedUntil = Instant.EPOCH;
    private volatile String lastLoggedStatus = "UNKNOWN";

    public Snapshot currentSnapshot() {
        Instant now = Instant.now();
        Snapshot snapshot = cachedSnapshot;
        if (snapshot != null && now.isBefore(cachedUntil)) {
            return snapshot;
        }
        synchronized (this) {
            now = Instant.now();
            snapshot = cachedSnapshot;
            if (snapshot != null && now.isBefore(cachedUntil)) {
                return snapshot;
            }
            snapshot = computeSnapshot();
            cachedSnapshot = snapshot;
            cachedUntil = now.plus(cacheTtl != null ? cacheTtl : Duration.ofSeconds(15));
            if (!Objects.equals(lastLoggedStatus, snapshot.status())) {
                logReadinessTransition(snapshot);
                lastLoggedStatus = snapshot.status();
            }
            return snapshot;
        }
    }

    private Snapshot computeSnapshot() {
        ComponentSnapshot stt = inspectStt();
        ComponentSnapshot tts = inspectTts();
        ComponentSnapshot assets = inspectAssets();
        ComponentSnapshot orchestrator = inspectOrchestrator();
        ComponentSnapshot websocket = inspectWebsocket();
        DownstreamRouteSnapshot downstream = inspectApiGatewayRoute();

        Map<String, ComponentSnapshot> componentDetails = new LinkedHashMap<>();
        componentDetails.put("stt", stt);
        componentDetails.put("tts", tts);
        componentDetails.put("assets", assets);
        componentDetails.put("orchestrator", orchestrator);
        componentDetails.put("websocket", websocket);

        Map<String, String> componentStatuses = new LinkedHashMap<>();
        componentDetails.forEach((name, component) -> componentStatuses.put(name, component.status()));

        String status = overallStatus(componentDetails);
        return new Snapshot(
                status,
                Map.copyOf(componentStatuses),
                Map.copyOf(componentDetails),
                downstream);
    }

    private String overallStatus(Map<String, ComponentSnapshot> components) {
        if (isDown(components.get("stt")) || isDown(components.get("websocket"))) {
            return STATUS_DOWN;
        }
        if (isDown(components.get("tts"))
                || isDown(components.get("assets"))
                || isDown(components.get("orchestrator"))) {
            return STATUS_DEGRADED;
        }
        return STATUS_UP;
    }

    private ComponentSnapshot inspectStt() {
        Map<String, Object> runtime = sttService.describeRuntime();
        boolean available = Boolean.TRUE.equals(runtime.get("available"));
        String provider = String.valueOf(runtime.getOrDefault("configuredProvider", "unknown"));
        if (!available) {
            return ComponentSnapshot.down("STT_PROVIDER_UNAVAILABLE", "STT provider is unavailable", runtime);
        }

        boolean selfTestPassed = runSttSelfTest();
        if (!selfTestPassed) {
            return ComponentSnapshot.down("STT_SELF_TEST_FAILED", "STT self-test inference failed", runtime);
        }

        return ComponentSnapshot.up("STT_READY", "STT provider " + provider + " passed model and inference checks", runtime);
    }

    private boolean runSttSelfTest() {
        try {
            sttService.transcribe(STT_SELF_TEST_PCM, canonicalRecognitionLanguage(defaultLanguage));
            return true;
        } catch (RuntimeException e) {
            log.warn("Voice readiness STT self-test failed: {}", e.getMessage());
            return false;
        }
    }

    private ComponentSnapshot inspectTts() {
        Map<String, Object> runtime = new LinkedHashMap<>(ttsService.describeRuntime());
        if (!Boolean.TRUE.equals(runtime.get("available"))) {
            return ComponentSnapshot.down("TTS_PROVIDER_UNAVAILABLE", String.valueOf(runtime.getOrDefault("reason", "TTS is unavailable")), runtime);
        }

        try {
            TtsService.SynthesisResult synthesis = ttsService.synthesizeDetailed(
                    "Jarvis readiness check",
                    "en-US",
                    "en-US-Wavenet-D",
                    1.0,
                    0.0);
            CanonicalWavAudio.Inspection inspection = CanonicalWavAudio.inspect(synthesis.audioData());
            runtime.put("selfTestStatus", synthesis.status());
            runtime.put("selfTestReason", synthesis.reason());
            runtime.put("selfTestAudioValid", inspection.valid());
            runtime.put("selfTestAudioCanonical", inspection.canonical());
            runtime.put("selfTestAudioSize", synthesis.audioData().length);
            if (!inspection.valid()) {
                return ComponentSnapshot.down("TTS_AUDIO_INVALID", "TTS generated invalid WAV output", runtime);
            }
            return ComponentSnapshot.up("TTS_READY", "TTS synthesis self-test succeeded", runtime);
        } catch (RuntimeException e) {
            runtime.put("selfTestError", e.getMessage());
            return ComponentSnapshot.down("TTS_SELF_TEST_FAILED", e.getMessage(), runtime);
        }
    }

    private ComponentSnapshot inspectAssets() {
        Set<String> referencedAssets = wavResponseRegistry.getReferencedAssetIds();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referencedAssets", referencedAssets.size());
        details.put("activeManifestAssets", voiceAssetLoader.getActiveAssetCount());

        int verified = 0;
        Map<String, Object> failures = new LinkedHashMap<>();
        for (String assetId : referencedAssets) {
            byte[] audio = voiceAssetLoader.load(assetId);
            if (audio == null || audio.length == 0) {
                failures.put(assetId, "missing_or_unloadable");
                continue;
            }
            CanonicalWavAudio.Inspection inspection = CanonicalWavAudio.inspect(audio);
            if (!inspection.valid()) {
                failures.put(assetId, inspection.reason());
                continue;
            }
            verified++;
        }
        details.put("verifiedAssets", verified);
        if (!failures.isEmpty()) {
            details.put("invalidAssets", failures);
            return ComponentSnapshot.down(
                    "VOICE_ASSETS_INVALID",
                    "One or more referenced voice assets are missing or invalid",
                    details);
        }
        return ComponentSnapshot.up("VOICE_ASSETS_READY", "Referenced voice assets are loadable and valid WAV", details);
    }

    private ComponentSnapshot inspectOrchestrator() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orchestratorUrl", orchestratorUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClientBuilder.build()
                    .get()
                    .uri(orchestratorUrl + "/actuator/health")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            details.put("status", response != null ? response.get("status") : null);
            if (response != null && STATUS_UP.equalsIgnoreCase(String.valueOf(response.get("status")))) {
                return ComponentSnapshot.up("ORCHESTRATOR_READY", "Orchestrator health endpoint is reachable", details);
            }
            return ComponentSnapshot.down("ORCHESTRATOR_HEALTH_DOWN", "Orchestrator did not report UP", details);
        } catch (RestClientException e) {
            details.put("error", e.getMessage());
            return ComponentSnapshot.down("ORCHESTRATOR_UNREACHABLE", e.getMessage(), details);
        }
    }

    private DownstreamRouteSnapshot inspectApiGatewayRoute() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("apiGatewayUrl", apiGatewayUrl);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClientBuilder.build()
                    .get()
                    .uri(apiGatewayUrl + "/actuator/health")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            String status = response != null ? String.valueOf(response.get("status")) : STATUS_DOWN;
            details.put("status", status);
            return new DownstreamRouteSnapshot(
                    STATUS_UP.equalsIgnoreCase(status) ? STATUS_UP : STATUS_DOWN,
                    STATUS_UP.equalsIgnoreCase(status) ? "API_GATEWAY_ROUTE_READY" : "API_GATEWAY_ROUTE_DOWN",
                    STATUS_UP.equalsIgnoreCase(status) ? "API Gateway health endpoint is reachable" : "API Gateway did not report UP",
                    immutableDetails(details));
        } catch (RestClientException e) {
            details.put("error", e.getMessage());
            return new DownstreamRouteSnapshot(
                    STATUS_DOWN,
                    "API_GATEWAY_UNREACHABLE",
                    e.getMessage(),
                    immutableDetails(details));
        }
    }

    private ComponentSnapshot inspectWebsocket() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", "ws://127.0.0.1:" + serverPort + "/ws/voice");
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
            container.setDefaultMaxTextMessageBufferSize(64 * 1024);

            StandardWebSocketClient client = new StandardWebSocketClient(container);
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            if (serviceJwtProvider.isEnabled()) {
                headers.add(ServiceJwtFilter.SERVICE_TOKEN_HEADER, serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
            }
            headers.add(HttpHeaders.USER_AGENT, "voice-readiness-probe");

            WebSocketSession session = client.execute(
                    new AbstractWebSocketHandler() {
                    },
                    headers,
                    URI.create("ws://127.0.0.1:" + serverPort + "/ws/voice"))
                    .get(3, TimeUnit.SECONDS);

            details.put("sessionId", session.getId());
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
            return ComponentSnapshot.up("WEBSOCKET_READY", "Loopback websocket handshake succeeded", details);
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            details.put("error", cause.getMessage());
            if (cause instanceof TimeoutException) {
                return ComponentSnapshot.down("WEBSOCKET_TIMEOUT", "WebSocket handshake timed out", details);
            }
            return ComponentSnapshot.down("WEBSOCKET_HANDSHAKE_FAILED", cause.getMessage(), details);
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isDown(ComponentSnapshot component) {
        return component != null && STATUS_DOWN.equals(component.status());
    }

    private void logReadinessTransition(Snapshot snapshot) {
        log.info(
                "Voice readiness status changed: status={}, components={}, apiGatewayRoute={}",
                snapshot.status(),
                snapshot.components(),
                snapshot.apiGatewayRoute().status());
        snapshot.componentDetails().forEach((name, component) -> {
            if (STATUS_DOWN.equals(component.status())) {
                log.warn("Voice readiness component down: component={}, reasonCode={}, message={}",
                        name, component.reasonCode(), component.message());
            }
        });
        if (STATUS_DOWN.equals(snapshot.apiGatewayRoute().status())) {
            log.warn("Voice downstream route degraded: reasonCode={}, message={}",
                    snapshot.apiGatewayRoute().reasonCode(),
                    snapshot.apiGatewayRoute().message());
        }
    }

    private String canonicalRecognitionLanguage(String language) {
        String fallback = defaultLanguage != null ? defaultLanguage : "ru-RU";
        if (language == null || language.isBlank()) {
            return fallback.toLowerCase(Locale.ROOT).startsWith("en") ? "en-US" : "ru-RU";
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return normalized.startsWith("en") ? "en-US" : "ru-RU";
    }

    public record Snapshot(
            String status,
            Map<String, String> components,
            Map<String, ComponentSnapshot> componentDetails,
            DownstreamRouteSnapshot apiGatewayRoute) {
    }

    public record ComponentSnapshot(
            String status,
            String reasonCode,
            String message,
            Map<String, Object> details) {

        static ComponentSnapshot up(String reasonCode, String message, Map<String, Object> details) {
            return new ComponentSnapshot(STATUS_UP, reasonCode, message, immutableDetails(details));
        }

        static ComponentSnapshot down(String reasonCode, String message, Map<String, Object> details) {
            return new ComponentSnapshot(STATUS_DOWN, reasonCode, message, immutableDetails(details));
        }
    }

    public record DownstreamRouteSnapshot(
            String status,
            String reasonCode,
            String message,
            Map<String, Object> details) {
    }

    private static Map<String, Object> immutableDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> cleaned = new LinkedHashMap<>();
        for (Entry<String, Object> entry : details.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(cleaned);
    }
}
