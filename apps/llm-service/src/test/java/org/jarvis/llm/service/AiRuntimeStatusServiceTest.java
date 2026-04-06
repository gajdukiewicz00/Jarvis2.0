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

        AiRuntimeStatusService service = new AiRuntimeStatusService(llmClient, memoryClient);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
