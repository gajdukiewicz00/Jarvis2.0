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

class AiRuntimeStatusServiceTest {

    @Test
    void describeReportsReadyCanonicalLocalStack() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);
        Path gpuStatusFile = Files.createTempFile("jarvis-ai-gpu-status", ".json");
        Files.writeString(gpuStatusFile, """
                {
                  "status": "verified",
                  "reason": "",
                  "verifiedAt": "2026-03-28T15:40:00Z",
                  "profile": "llama-cpp-python==0.3.19 + N_GPU_LAYERS=-1",
                  "packageSpec": "llama-cpp-python==0.3.19",
                  "modelPath": "/home/test/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf",
                  "effectiveDevicePath": "cuda",
                  "effectiveGpuLayers": 36,
                  "llamaCppPythonVersion": "0.3.19",
                  "driverVersion": "590.48.01",
                  "gpuName": "NVIDIA GeForce RTX 5070"
                }
                """);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true,
                "healthy",
                "llamacpp",
                true,
                "cuda",
                true,
                "12.4",
                "qwen2.5-3b-instruct-q4_k_m.gguf",
                "/home/test/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf",
                Map.of(
                        "model_path", "/home/test/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf",
                        "effective_n_gpu_layers", 36
                ),
                null));
        when(memoryClient.getHealth()).thenReturn(new MemoryClient.MemoryServiceHealth(
                true,
                "healthy",
                true,
                true,
                true,
                "http://127.0.0.1:8093",
                "/home/test/.jarvis/models/embeddings/intfloat-multilingual-e5-small",
                384,
                null,
                null));

        LlmLifecycleManager lifecycleManager = new LlmLifecycleManager(llmClient, memoryClient);
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);
        ReflectionTestUtils.setField(lifecycleManager, "memoryEnabled", true);
        lifecycleManager.refreshState();
        LlmAdmissionController admissionController = new LlmAdmissionController(1, 8);
        AiRuntimeStatusService service = new AiRuntimeStatusService(llmClient, memoryClient, lifecycleManager, admissionController);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmBaseUrl", "http://127.0.0.1:15000");
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceUrl", "http://127.0.0.1:8093");
        ReflectionTestUtils.setField(service, "configuredLlmProvider", "llamacpp");
        ReflectionTestUtils.setField(service, "configuredLlmModelId", "Qwen/Qwen2.5-3B-Instruct-GGUF");
        ReflectionTestUtils.setField(service, "configuredLlmModelPath", "/home/test/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelId", "intfloat/multilingual-e5-small");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelPath", "/home/test/.jarvis/models/embeddings/intfloat-multilingual-e5-small");
        ReflectionTestUtils.setField(service, "canonicalLocalAiStack", "qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector");
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "auto");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", -1);
        ReflectionTestUtils.setField(service, "configuredLlamaCppPackageSpec", "llama-cpp-python==0.3.19");
        ReflectionTestUtils.setField(service, "gpuStatusFile", gpuStatusFile.toString());
        ReflectionTestUtils.setField(service, "datasourceUrl", "jdbc:postgresql://127.0.0.1:5432/jarvis");

        Map<String, Object> payload = service.describe();

        assertThat(payload).containsEntry("status", "ready");
        assertThat(payload).containsEntry("fullLocalAiReadiness", true);
        Map<String, Object> localDefaultStack = castMap(payload.get("localDefaultStack"));
        Map<String, Object> llm = castMap(payload.get("llm"));
        Map<String, Object> gpu = castMap(payload.get("gpu"));
        Map<String, Object> memory = castMap(payload.get("memory"));
        Map<String, Object> embedding = castMap(payload.get("embedding"));
        Map<String, Object> vectorStore = castMap(payload.get("vectorStore"));

        assertThat(localDefaultStack.get("id"))
                .isEqualTo("qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector");
        assertThat(localDefaultStack.get("fullLocalAiReadiness")).isEqualTo(true);
        assertThat(llm.get("configuredProvider")).isEqualTo("llamacpp");
        assertThat(llm.get("configuredDevicePath")).isEqualTo("cuda");
        assertThat(llm.get("effectiveDevicePath")).isEqualTo("cuda");
        assertThat(llm.get("configuredGpuLayers")).isEqualTo(-1);
        assertThat(llm.get("effectiveGpuLayers")).isEqualTo(36);
        assertThat(llm.get("available")).isEqualTo(true);
        assertThat(gpu.get("readinessStatus")).isEqualTo("verified");
        assertThat(gpu.get("verifiedGpuLayers")).isEqualTo(36);
        assertThat(castMap(gpu.get("canonicalCpuBaseline")).get("nGpuLayers")).isEqualTo(0);
        assertThat(memory.get("available")).isEqualTo(true);
        assertThat(embedding.get("available")).isEqualTo(true);
        assertThat(embedding.get("dimension")).isEqualTo(384);
        assertThat(vectorStore.get("available")).isEqualTo(true);
        assertThat(vectorStore.get("pgvectorAvailable")).isEqualTo(true);
    }

    @Test
    void describeReportsDegradedWhenMemoryServiceDown() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model.gguf", "/path/model.gguf",
                Map.of("model_path", "/path/model.gguf"), null));
        when(memoryClient.getHealth()).thenReturn(new MemoryClient.MemoryServiceHealth(
                false, "error", false, false, false,
                "http://127.0.0.1:8093", null, null, null,
                "connection refused"));
        when(memoryClient.isHealthy()).thenReturn(false);

        LlmLifecycleManager lifecycleManager = new LlmLifecycleManager(llmClient, memoryClient);
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);
        ReflectionTestUtils.setField(lifecycleManager, "memoryEnabled", true);
        lifecycleManager.refreshState();

        LlmAdmissionController admissionController = new LlmAdmissionController(1, 8);
        AiRuntimeStatusService service = new AiRuntimeStatusService(
                llmClient, memoryClient, lifecycleManager, admissionController);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmBaseUrl", "http://127.0.0.1:15000");
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceUrl", "http://127.0.0.1:8093");
        ReflectionTestUtils.setField(service, "configuredLlmProvider", "llamacpp");
        ReflectionTestUtils.setField(service, "configuredLlmModelId", "test-model");
        ReflectionTestUtils.setField(service, "configuredLlmModelPath", "/path/model.gguf");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelId", "test-embed");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelPath", "");
        ReflectionTestUtils.setField(service, "canonicalLocalAiStack", "test-stack");
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "cpu");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", 0);
        ReflectionTestUtils.setField(service, "configuredLlamaCppPackageSpec", "llama-cpp-python==0.3.19");
        ReflectionTestUtils.setField(service, "gpuStatusFile", "");
        ReflectionTestUtils.setField(service, "datasourceUrl", "");

        Map<String, Object> payload = service.describe();

        assertThat(payload).containsEntry("status", "partial");
        assertThat(payload).containsEntry("fullLocalAiReadiness", false);

        Map<String, Object> lifecycle = castMap(payload.get("lifecycle"));
        assertThat(lifecycle.get("state")).isEqualTo("DEGRADED");
        assertThat(lifecycle.get("usable")).isEqualTo(true);
        assertThat(lifecycle.get("reason")).isEqualTo("memory-service unavailable");

        Map<String, Object> memory = castMap(payload.get("memory"));
        assertThat(memory.get("available")).isEqualTo(false);
        assertThat(memory.get("reason")).asString().contains("connection refused");

        Map<String, Object> llm = castMap(payload.get("llm"));
        assertThat(llm.get("available")).isEqualTo(true);
    }

    @Test
    void describeReportsEmbeddingDownWhileMemoryDbUp() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model.gguf", "/path/model.gguf",
                Map.of(), null));
        when(memoryClient.getHealth()).thenReturn(new MemoryClient.MemoryServiceHealth(
                false, "degraded",
                true,   // databaseUp
                true,   // pgvectorAvailable
                false,  // embeddingServiceUp — embedding is DOWN
                "http://127.0.0.1:8093", null, null,
                "embedding-service connection refused", null));
        when(memoryClient.isHealthy()).thenReturn(false);

        LlmLifecycleManager lifecycleManager = new LlmLifecycleManager(llmClient, memoryClient);
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);
        ReflectionTestUtils.setField(lifecycleManager, "memoryEnabled", true);
        lifecycleManager.refreshState();

        LlmAdmissionController admissionController = new LlmAdmissionController(1, 8);
        AiRuntimeStatusService service = new AiRuntimeStatusService(
                llmClient, memoryClient, lifecycleManager, admissionController);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmBaseUrl", "http://127.0.0.1:15000");
        ReflectionTestUtils.setField(service, "memoryEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", true);
        ReflectionTestUtils.setField(service, "memoryServiceUrl", "http://127.0.0.1:8093");
        ReflectionTestUtils.setField(service, "configuredLlmProvider", "llamacpp");
        ReflectionTestUtils.setField(service, "configuredLlmModelId", "test-model");
        ReflectionTestUtils.setField(service, "configuredLlmModelPath", "/path/model.gguf");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelId", "test-embed");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelPath", "");
        ReflectionTestUtils.setField(service, "canonicalLocalAiStack", "test-stack");
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "cpu");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", 0);
        ReflectionTestUtils.setField(service, "configuredLlamaCppPackageSpec", "llama-cpp-python==0.3.19");
        ReflectionTestUtils.setField(service, "gpuStatusFile", "");
        ReflectionTestUtils.setField(service, "datasourceUrl", "");

        Map<String, Object> payload = service.describe();

        Map<String, Object> embedding = castMap(payload.get("embedding"));
        assertThat(embedding.get("available")).isEqualTo(false);
        assertThat(embedding.get("reason")).asString().contains("embedding-service connection refused");

        Map<String, Object> vectorStore = castMap(payload.get("vectorStore"));
        assertThat(vectorStore.get("databaseUp")).isEqualTo(true);
        assertThat(vectorStore.get("pgvectorAvailable")).isEqualTo(true);
        assertThat(vectorStore.get("available")).isEqualTo(true);

        Map<String, Object> memory = castMap(payload.get("memory"));
        assertThat(memory.get("available")).isEqualTo(false);
        assertThat(memory.get("reason")).asString().contains("embedding-service connection refused");

        Map<String, Object> lifecycle = castMap(payload.get("lifecycle"));
        assertThat(lifecycle.get("state")).isEqualTo("DEGRADED");
        assertThat(lifecycle.get("usable")).isEqualTo(true);
    }

    @Test
    void describeReportsLlmOnlyWhenMemoryDisabled() {
        LlmClient llmClient = mock(LlmClient.class);
        MemoryClient memoryClient = mock(MemoryClient.class);

        when(llmClient.getHealth()).thenReturn(new LlmClient.LlmServerHealth(
                true, "healthy", "llamacpp", true, "cpu", false, null,
                "model.gguf", "/path/model.gguf",
                Map.of(), null));
        when(memoryClient.getHealth()).thenReturn(new MemoryClient.MemoryServiceHealth(
                false, "disabled", false, false, false,
                "http://127.0.0.1:8093", null, null, null,
                "memory.service.enabled=false"));

        LlmLifecycleManager lifecycleManager = new LlmLifecycleManager(llmClient, memoryClient);
        ReflectionTestUtils.setField(lifecycleManager, "llmEnabled", true);
        ReflectionTestUtils.setField(lifecycleManager, "memoryEnabled", false);
        lifecycleManager.refreshState();

        LlmAdmissionController admissionController = new LlmAdmissionController(1, 8);
        AiRuntimeStatusService service = new AiRuntimeStatusService(
                llmClient, memoryClient, lifecycleManager, admissionController);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmBaseUrl", "http://127.0.0.1:15000");
        ReflectionTestUtils.setField(service, "memoryEnabled", false);
        ReflectionTestUtils.setField(service, "memoryServiceEnabled", false);
        ReflectionTestUtils.setField(service, "memoryServiceUrl", "http://127.0.0.1:8093");
        ReflectionTestUtils.setField(service, "configuredLlmProvider", "llamacpp");
        ReflectionTestUtils.setField(service, "configuredLlmModelId", "test-model");
        ReflectionTestUtils.setField(service, "configuredLlmModelPath", "/path/model.gguf");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelId", "test-embed");
        ReflectionTestUtils.setField(service, "configuredEmbeddingModelPath", "");
        ReflectionTestUtils.setField(service, "canonicalLocalAiStack", "test-stack");
        ReflectionTestUtils.setField(service, "configuredDeviceSetting", "cpu");
        ReflectionTestUtils.setField(service, "configuredGpuLayers", 0);
        ReflectionTestUtils.setField(service, "configuredLlamaCppPackageSpec", "llama-cpp-python==0.3.19");
        ReflectionTestUtils.setField(service, "gpuStatusFile", "");
        ReflectionTestUtils.setField(service, "datasourceUrl", "");

        Map<String, Object> payload = service.describe();

        assertThat(payload).containsEntry("status", "llm-only");

        Map<String, Object> lifecycle = castMap(payload.get("lifecycle"));
        assertThat(lifecycle.get("state")).isEqualTo("READY");
        assertThat(lifecycle.get("usable")).isEqualTo(true);

        Map<String, Object> llm = castMap(payload.get("llm"));
        assertThat(llm.get("available")).isEqualTo(true);

        Map<String, Object> memory = castMap(payload.get("memory"));
        assertThat(memory.get("available")).isEqualTo(false);
        assertThat(memory.get("reason")).asString().contains("memory disabled");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
