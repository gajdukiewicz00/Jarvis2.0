package org.jarvis.voicegateway.health;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.audio.CanonicalWavAudio;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Covers VoiceReadinessService's private inspectXxx() branches (invoked directly
 * via reflection, since each reads only constructor-injected collaborators and
 * takes no arguments) plus the currentSnapshot() caching wrapper end-to-end.
 * VoiceReadinessServiceTest already covers overallStatus() aggregation logic.
 */
class VoiceReadinessServiceComputeSnapshotTest {

    private SttService sttService;
    private TtsService ttsService;
    private VoiceAssetLoader voiceAssetLoader;
    private WavResponseRegistry wavResponseRegistry;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private ServiceJwtProvider serviceJwtProvider;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ConnectionFactory> rabbitProvider = mock(ObjectProvider.class);
    private VoiceReadinessService service;

    @BeforeEach
    void setUp() {
        sttService = mock(SttService.class);
        ttsService = mock(TtsService.class);
        voiceAssetLoader = mock(VoiceAssetLoader.class);
        wavResponseRegistry = mock(WavResponseRegistry.class);
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();

        service = new VoiceReadinessService(
                sttService, ttsService, voiceAssetLoader, wavResponseRegistry,
                restClientBuilder, serviceJwtProvider, rabbitProvider);
        ReflectionTestUtils.setField(service, "orchestratorUrl", "http://test-orchestrator");
        ReflectionTestUtils.setField(service, "apiGatewayUrl", "http://test-api-gateway");
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");
    }

    private VoiceReadinessService.ComponentSnapshot invoke(String method) {
        return ReflectionTestUtils.invokeMethod(service, method);
    }

    // ==================== inspectStt ====================

    @Test
    void inspectSttReportsDownWhenProviderUnavailable() {
        when(sttService.describeRuntime()).thenReturn(Map.of("available", false, "configuredProvider", "vosk"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectStt");

        assertEquals("DOWN", snapshot.status());
        assertEquals("STT_PROVIDER_UNAVAILABLE", snapshot.reasonCode());
    }

    @Test
    void inspectSttReportsUpWhenSelfTestSucceeds() {
        when(sttService.describeRuntime()).thenReturn(Map.of("available", true, "configuredProvider", "vosk"));
        when(sttService.transcribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("привет");

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectStt");

        assertEquals("UP", snapshot.status());
        assertEquals("STT_READY", snapshot.reasonCode());
    }

    @Test
    void inspectSttReportsDownWhenSelfTestThrows() {
        when(sttService.describeRuntime()).thenReturn(Map.of("available", true, "configuredProvider", "vosk"));
        when(sttService.transcribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("model crashed"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectStt");

        assertEquals("DOWN", snapshot.status());
        assertEquals("STT_SELF_TEST_FAILED", snapshot.reasonCode());
    }

    // ==================== inspectTts ====================

    @Test
    void inspectTtsReportsDownWhenProviderUnavailable() {
        when(ttsService.describeRuntime()).thenReturn(Map.of("available", false, "reason", "espeak missing"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectTts");

        assertEquals("DOWN", snapshot.status());
        assertEquals("TTS_PROVIDER_UNAVAILABLE", snapshot.reasonCode());
    }

    @Test
    void inspectTtsReportsUpWhenSynthesisProducesValidCanonicalAudio() throws Exception {
        when(ttsService.describeRuntime()).thenReturn(Map.of("available", true));
        when(ttsService.synthesizeDetailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(new TtsService.SynthesisResult(canonicalWavFixture(), "espeak", "espeak", "available", "ready"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectTts");

        assertEquals("UP", snapshot.status());
        assertEquals("TTS_READY", snapshot.reasonCode());
    }

    @Test
    void inspectTtsReportsDownWhenSynthesisProducesInvalidAudio() {
        when(ttsService.describeRuntime()).thenReturn(Map.of("available", true));
        when(ttsService.synthesizeDetailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(new TtsService.SynthesisResult(new byte[] {1, 2, 3}, "espeak", "espeak", "available", "ready"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectTts");

        assertEquals("DOWN", snapshot.status());
        assertEquals("TTS_AUDIO_INVALID", snapshot.reasonCode());
    }

    @Test
    void inspectTtsReportsDownWhenSynthesisThrows() {
        when(ttsService.describeRuntime()).thenReturn(Map.of("available", true));
        when(ttsService.synthesizeDetailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenThrow(new RuntimeException("synth failed"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectTts");

        assertEquals("DOWN", snapshot.status());
        assertEquals("TTS_SELF_TEST_FAILED", snapshot.reasonCode());
    }

    // ==================== inspectAssets ====================

    @Test
    void inspectAssetsReportsUpWhenAllReferencedAssetsAreValid() throws Exception {
        when(wavResponseRegistry.getReferencedAssetIds()).thenReturn(Set.of("ru/system/yes_sir"));
        when(voiceAssetLoader.load("ru/system/yes_sir")).thenReturn(canonicalWavFixture());

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectAssets");

        assertEquals("UP", snapshot.status());
        assertEquals("VOICE_ASSETS_READY", snapshot.reasonCode());
    }

    @Test
    void inspectAssetsReportsDownWhenAssetIsMissing() {
        when(wavResponseRegistry.getReferencedAssetIds()).thenReturn(Set.of("ru/system/missing"));
        when(voiceAssetLoader.load("ru/system/missing")).thenReturn(null);

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectAssets");

        assertEquals("DOWN", snapshot.status());
        assertEquals("VOICE_ASSETS_INVALID", snapshot.reasonCode());
    }

    @Test
    void inspectAssetsReportsDownWhenAssetBytesAreNotValidWav() {
        when(wavResponseRegistry.getReferencedAssetIds()).thenReturn(Set.of("ru/system/corrupt"));
        when(voiceAssetLoader.load("ru/system/corrupt")).thenReturn(new byte[] {9, 9, 9});

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectAssets");

        assertEquals("DOWN", snapshot.status());
        assertEquals("VOICE_ASSETS_INVALID", snapshot.reasonCode());
    }

    // ==================== inspectOrchestrator ====================

    @Test
    void inspectOrchestratorReportsUpWhenHealthEndpointReportsUp() {
        server.expect(requestTo("http://test-orchestrator/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"UP\"}"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectOrchestrator");

        assertEquals("UP", snapshot.status());
        assertEquals("ORCHESTRATOR_READY", snapshot.reasonCode());
    }

    @Test
    void inspectOrchestratorReportsDownWhenHealthEndpointReportsNonUp() {
        server.expect(requestTo("http://test-orchestrator/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"DOWN\"}"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectOrchestrator");

        assertEquals("DOWN", snapshot.status());
        assertEquals("ORCHESTRATOR_HEALTH_DOWN", snapshot.reasonCode());
    }

    @Test
    void inspectOrchestratorReportsDownWhenUnreachable() {
        server.expect(requestTo("http://test-orchestrator/actuator/health"))
                .andRespond(request -> {
                    throw new java.io.IOException("refused");
                });

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectOrchestrator");

        assertEquals("DOWN", snapshot.status());
        assertEquals("ORCHESTRATOR_UNREACHABLE", snapshot.reasonCode());
    }

    // ==================== inspectApiGatewayRoute ====================

    @Test
    void inspectApiGatewayRouteReportsUpWhenHealthy() {
        server.expect(requestTo("http://test-api-gateway/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"UP\"}"));

        VoiceReadinessService.DownstreamRouteSnapshot snapshot =
                ReflectionTestUtils.invokeMethod(service, "inspectApiGatewayRoute");

        assertEquals("UP", snapshot.status());
        assertEquals("API_GATEWAY_ROUTE_READY", snapshot.reasonCode());
    }

    @Test
    void inspectApiGatewayRouteReportsDownWhenNotUp() {
        server.expect(requestTo("http://test-api-gateway/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"DOWN\"}"));

        VoiceReadinessService.DownstreamRouteSnapshot snapshot =
                ReflectionTestUtils.invokeMethod(service, "inspectApiGatewayRoute");

        assertEquals("DOWN", snapshot.status());
        assertEquals("API_GATEWAY_ROUTE_DOWN", snapshot.reasonCode());
    }

    @Test
    void inspectApiGatewayRouteReportsDownWhenUnreachable() {
        server.expect(requestTo("http://test-api-gateway/actuator/health"))
                .andRespond(request -> {
                    throw new java.io.IOException("refused");
                });

        VoiceReadinessService.DownstreamRouteSnapshot snapshot =
                ReflectionTestUtils.invokeMethod(service, "inspectApiGatewayRoute");

        assertEquals("DOWN", snapshot.status());
        assertEquals("API_GATEWAY_UNREACHABLE", snapshot.reasonCode());
    }

    // ==================== inspectRabbit ====================

    @Test
    void inspectRabbitReportsDownWhenNoConnectionFactoryConfigured() {
        when(rabbitProvider.getIfAvailable()).thenReturn(null);

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectRabbit");

        assertEquals("DOWN", snapshot.status());
        assertEquals("RABBIT_NOT_CONFIGURED", snapshot.reasonCode());
    }

    @Test
    void inspectRabbitReportsUpWhenConnectionIsOpen() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectRabbit");

        assertEquals("UP", snapshot.status());
        assertEquals("RABBIT_READY", snapshot.reasonCode());
        verify(connection).close();
    }

    @Test
    void inspectRabbitReportsDownWhenConnectionReportsClosed() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(false);

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectRabbit");

        assertEquals("DOWN", snapshot.status());
        assertEquals("RABBIT_CONNECTION_CLOSED", snapshot.reasonCode());
    }

    @Test
    void inspectRabbitReportsDownWhenConnectionFails() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(rabbitProvider.getIfAvailable()).thenReturn(factory);
        when(factory.createConnection()).thenThrow(new RuntimeException("broker unreachable"));

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectRabbit");

        assertEquals("DOWN", snapshot.status());
        assertEquals("RABBIT_UNREACHABLE", snapshot.reasonCode());
    }

    // ==================== inspectWebsocket ====================

    @Test
    void inspectWebsocketReportsDownWhenNoServerIsListening() {
        // No real HTTP/WebSocket server is bound to serverPort in this unit test, so the
        // real StandardWebSocketClient handshake genuinely fails (connection refused or
        // timeout) — this exercises the real failure branch and the rootCause() unwrapping.
        ReflectionTestUtils.setField(service, "serverPort", 1);

        VoiceReadinessService.ComponentSnapshot snapshot = invoke("inspectWebsocket");

        assertEquals("DOWN", snapshot.status());
        assertTrue(Set.of("WEBSOCKET_HANDSHAKE_FAILED", "WEBSOCKET_TIMEOUT").contains(snapshot.reasonCode()));
    }

    // ==================== currentSnapshot caching ====================

    @Test
    void currentSnapshotCachesWithinTtlAndRecomputesAfterExpiry() {
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "serverPort", 1);
        stubAllComponentsUp();

        VoiceReadinessService.Snapshot first = service.currentSnapshot();
        VoiceReadinessService.Snapshot second = service.currentSnapshot();

        assertEquals(first, second);
        verify(sttService, times(1)).describeRuntime();

        // Force expiry and verify a fresh computation happens.
        ReflectionTestUtils.setField(service, "cachedUntil", java.time.Instant.EPOCH);
        service.currentSnapshot();

        verify(sttService, times(2)).describeRuntime();
    }

    private void stubAllComponentsUp() {
        when(sttService.describeRuntime()).thenReturn(Map.of("available", true, "configuredProvider", "vosk"));
        when(sttService.transcribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("привет");
        when(ttsService.describeRuntime()).thenReturn(Map.of("available", false, "reason", "not needed for this test"));
        when(wavResponseRegistry.getReferencedAssetIds()).thenReturn(Set.of());
        when(rabbitProvider.getIfAvailable()).thenReturn(null);
        // computeSnapshot() runs twice across this test (once cached-and-reused, once more
        // after forced expiry), so each downstream health endpoint is hit twice in total.
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                        requestTo("http://test-orchestrator/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"UP\"}"));
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                        requestTo("http://test-api-gateway/actuator/health"))
                .andRespond(withStatus(OK).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"UP\"}"));
    }

    private static byte[] canonicalWavFixture() throws Exception {
        byte[] pcm = new byte[640];
        AudioFormat format = CanonicalWavAudio.canonicalFormat();
        try (AudioInputStream audioInputStream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.sound.sampled.AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }
}
