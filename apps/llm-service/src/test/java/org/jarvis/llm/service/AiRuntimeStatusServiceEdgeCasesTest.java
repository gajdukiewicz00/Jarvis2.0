package org.jarvis.llm.service;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRuntimeStatusServiceEdgeCasesTest {

    private static final String MODEL_PATH = "/path/model.gguf";
    private static final String PACKAGE_SPEC = "llama-cpp-python==0.3.19";

    private static LlmClient.LlmServerHealth llmHealth(boolean available, Boolean gpuAvailable,
                                                       Map<String, Object> diagnostics) {
        return new LlmClient.LlmServerHealth(
                available, available ? "healthy" : "down", "llamacpp", available,
                available ? "cpu" : null, gpuAvailable, null,
                "model.gguf", MODEL_PATH, diagnostics, available ? null : "connection refused");
    }

    private static MemoryClient.MemoryServiceHealth memoryHealth(boolean available, boolean dbUp,
                                                                 boolean pgvector, boolean embedUp,
                                                                 String error) {
        return new MemoryClient.MemoryServiceHealth(
                available, available ? "healthy" : "error", dbUp, pgvector, embedUp,
                "http://127.0.0.1:8093", "embed-model", 384, null, error);
    }

    /**
     * Build a service wired with the given mocks, enabled, memory disabled by default.
     * Only overrides what matters for these edge cases; other fields keep Java defaults.
     */
    private AiRuntimeStatusService buildService(LlmClient llmClient, MemoryClient memoryClient) {
        LlmLifecycleManager lifecycle = new LlmLifecycleManager(llmClient, memoryClient);
        ReflectionTestUtils.setField(lifecycle, "llmEnabled", true);
        lifecycle.refreshState();

        LlmAdmissionController admission = new LlmAdmissionController(1, 8);
        AiRuntimeStatusService service =
                new AiRuntimeStatusService(llmClient, memoryClient, lifecycle, admission);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmBaseUrl", "http://127.0.0.1:15000");
        ReflectionTestUtils.setField(service, "configuredLlmProvider", "llamacpp");
        ReflectionTestUtils.setField(service, "configuredLlmModelId", "test-model");
        ReflectionTestUtils.setField(service, "configuredLlmModelPath", MODEL_PATH);
        ReflectionTestUtils.setField(service, "configuredLlamaCppPackageSpec", PACKAGE_SPEC);
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelId", "test-embed");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelPath", "");
        ReflectionTestUtils.setField(service, "canonicalLocalAiStack", "test-stack");
        ReflectionTestUtils.setField(service, "datasourceUrl", "");
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void degradedTopLevelStatusWhenLlmBackendUnavailable() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(false, false, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        Map<String, Object> payload = service.describe();

        assertThat(payload).containsEntry("status", "degraded");
        Map<String, Object> llm = castMap(payload.get("llm"));
        assertThat(llm.get("available")).isEqualTo(false);
        assertThat(llm.get("reason")).isEqualTo("connection refused");
    }

    @Test
    void gpuReadinessMarkedStaleOnPackageSpecMismatch() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        Path file = Files.createTempFile("gpu-status", ".json");
        Files.writeString(file, """
                {
                  "status": "verified",
                  "packageSpec": "llama-cpp-python==0.2.0",
                  "modelPath": "/path/model.gguf",
                  "effectiveDevicePath": "cuda",
                  "effectiveGpuLayers": 20
                }
                """);

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", file.toString());

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("stale");
        assertThat(gpu.get("readinessReason")).asString().contains("llama-cpp-python==0.2.0");
    }

    @Test
    void gpuReadinessMarkedStaleOnModelPathMismatch() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        Path file = Files.createTempFile("gpu-status", ".json");
        Files.writeString(file, """
                {
                  "status": "verified",
                  "packageSpec": "llama-cpp-python==0.3.19",
                  "modelPath": "/different/model.gguf",
                  "effectiveGpuLayers": 20
                }
                """);

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", file.toString());

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("stale");
        assertThat(gpu.get("readinessReason")).asString().contains("different GGUF model path");
    }

    @Test
    void gpuReadinessMarkedUnavailableWhenGpuNotDetectedDespiteFile() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, false, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        Path file = Files.createTempFile("gpu-status", ".json");
        Files.writeString(file, """
                {
                  "status": "verified",
                  "packageSpec": "llama-cpp-python==0.3.19",
                  "modelPath": "/path/model.gguf",
                  "effectiveGpuLayers": 20
                }
                """);

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", file.toString());

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("unavailable");
        assertThat(gpu.get("readinessReason")).asString().contains("GPU not detected");
    }

    @Test
    void gpuReadinessMarkedErrorOnUnparseableFile() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        Path file = Files.createTempFile("gpu-status", ".json");
        Files.writeString(file, "{ this is not valid json ");

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", file.toString());

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("error");
        assertThat(gpu.get("readinessReason")).asString().contains("failed to parse gpu readiness file");
    }

    @Test
    void gpuReadinessUnknownWhenNoStatusFileButGpuAvailable() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", "");

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("unknown");
        assertThat(gpu.get("readinessReason")).asString().contains("has not been run");
    }

    @Test
    void gpuReadinessMissingFilePathReturnsBaselinePayload() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, false, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "gpuStatusFile", "/nonexistent/path/gpu-status.json");

        Map<String, Object> gpu = castMap(service.describe().get("gpu"));
        assertThat(gpu.get("readinessStatus")).isEqualTo("unavailable");
        assertThat(gpu.get("readinessReason")).asString().contains("GPU not detected");
    }

    @Test
    void effectiveGpuLayersParsedFromStringDiagnostic() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(
                llmHealth(true, true, Map.of("effective_n_gpu_layers", "24")));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        Map<String, Object> llm = castMap(service.describe().get("llm"));
        assertThat(llm.get("effectiveGpuLayers")).isEqualTo(24);
    }

    @Test
    void invalidStringDiagnosticYieldsNullGpuLayers() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(
                llmHealth(true, true, Map.of("n_gpu_layers", "not-a-number")));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        Map<String, Object> llm = castMap(service.describe().get("llm"));
        assertThat(llm.get("effectiveGpuLayers")).isNull();
    }

    @Test
    void configuredDevicePathHonorsExplicitCudaSettingWhenGpuLayersNonZero() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "cuda");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", 24);

        Map<String, Object> llm = castMap(service.describe().get("llm"));
        assertThat(llm.get("configuredDevicePath")).isEqualTo("cuda");
    }

    @Test
    void configuredDevicePathResolvesToCudaForAutoWithGpuLayers() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, false, false, "off"));

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "auto");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", 10);

        Map<String, Object> llm = castMap(service.describe().get("llm"));
        assertThat(llm.get("configuredDevicePath")).isEqualTo("cuda");
    }

    @Test
    void memoryReasonReportsDatabaseUnavailable() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        // database down, no explicit error message -> reason derived from flags
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, false, true, true, null));
        when(memoryClient.isHealthy()).thenReturn(false);

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", true);

        Map<String, Object> payload = service.describe();
        Map<String, Object> memory = castMap(payload.get("memory"));
        assertThat(memory.get("reason")).isEqualTo("database unavailable");

        Map<String, Object> vectorStore = castMap(payload.get("vectorStore"));
        assertThat(vectorStore.get("reason")).isEqualTo("database unavailable");
    }

    @Test
    void memoryReasonReportsPgvectorUnavailable() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        when(llmClient.getHealth()).thenReturn(llmHealth(true, true, Map.of()));
        // db up, pgvector down, embedding up, no explicit error
        when(memoryClient.getHealth()).thenReturn(memoryHealth(false, true, false, true, null));
        when(memoryClient.isHealthy()).thenReturn(false);

        AiRuntimeStatusService service = buildService(llmClient, memoryClient);
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", true);

        Map<String, Object> payload = service.describe();
        Map<String, Object> memory = castMap(payload.get("memory"));
        assertThat(memory.get("reason")).isEqualTo("pgvector unavailable");

        Map<String, Object> vectorStore = castMap(payload.get("vectorStore"));
        assertThat(vectorStore.get("reason")).isEqualTo("pgvector unavailable");
    }
}
