package org.jarvis.llm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiRuntimeStatusService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final MemoryClient memoryClient;
    private final LlmLifecycleManager lifecycleManager;
    private final LlmAdmissionController admissionController;

    @Value("${jarvis.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${llm.base-url:http://127.0.0.1:15000}")
    private String llmBaseUrl;

    @Value("${memory.enabled:false}")
    private boolean memoryEnabled;

    @Value("${memory.service.enabled:false}")
    private boolean memoryServiceEnabled;

    @Value("${memory.service.url:http://127.0.0.1:8093}")
    private String memoryServiceUrl;

    @Value("${LLM_BACKEND:llamacpp}")
    private String configuredLlmProvider;

    @Value("${JARVIS_LLM_MODEL_ID:Qwen/Qwen2.5-3B-Instruct-GGUF}")
    private String configuredLlmModelId;

    @Value("${JARVIS_LLM_MODEL_PATH:}")
    private String configuredLlmModelPath;

    @Value("${JARVIS_EMBEDDING_MODEL_ID:intfloat/multilingual-e5-small}")
    private String configuredEmbeddingModelId;

    @Value("${JARVIS_EMBEDDING_MODEL_PATH:}")
    private String configuredEmbeddingModelPath;

    @Value("${JARVIS_CANONICAL_LOCAL_AI_STACK:qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector}")
    private String canonicalLocalAiStack;

    @Value("${DEVICE:auto}")
    private String configuredDeviceSetting;

    @Value("${N_GPU_LAYERS:0}")
    private Integer configuredGpuLayers;

    @Value("${JARVIS_LLAMACPP_PACKAGE_SPEC:llama-cpp-python==0.3.19}")
    private String configuredLlamaCppPackageSpec;

    @Value("${JARVIS_AI_GPU_STATUS_FILE:}")
    private String gpuStatusFile;

    @Value("${SPRING_DATASOURCE_URL:}")
    private String datasourceUrl;

    public Map<String, Object> describe() {
        LlmClient.LlmServerHealth llmHealth = llmClient.getHealth();
        MemoryClient.MemoryServiceHealth memoryHealth = memoryClient.getHealth();
        Map<String, Object> llmDiagnostics = llmHealth.diagnostics();
        Integer effectiveGpuLayers = integerValue(
                llmDiagnostics.getOrDefault("effective_n_gpu_layers", llmDiagnostics.get("n_gpu_layers"))
        );
        String configuredDevicePath = resolveConfiguredDevicePath();
        String effectiveDevicePath = blankToEmpty(llmHealth.device());

        boolean memoryRequired = memoryEnabled && memoryServiceEnabled;
        boolean llmAvailable = llmEnabled && llmHealth.available();
        boolean memoryAvailable = memoryRequired && memoryHealth.available();
        boolean fullLocalAiReadiness = llmAvailable && memoryAvailable;

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("llmServiceBasePath", "/api/v1/llm");
        routing.put("runtimePath", "/api/v1/llm/runtime");
        routing.put("chatPath", "/api/v1/llm/chat");
        routing.put("dialogPath", "/api/v1/llm/dialog");
        routing.put("orchestratorPath", "/api/v1/llm/orchestrate");
        routing.put("llmServerHealthUrl", llmBaseUrl + "/health");
        routing.put("memoryServiceHealthUrl", memoryServiceUrl + "/memory/health");

        Map<String, Object> maturity = new LinkedHashMap<>();
        maturity.put("llmChatPath", llmAvailable ? "verified" : "unavailable");
        maturity.put("dialogPath", llmAvailable ? "verified" : "unavailable");
        maturity.put("memoryRetrievalPath", memoryRequired ? (memoryAvailable ? "verified" : "unavailable") : "disabled");
        maturity.put("memoryWritePath", memoryRequired ? (memoryAvailable ? "verified" : "unavailable") : "disabled");
        maturity.put("orchestratorFallbackPath", llmEnabled ? "enabled" : "disabled");

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("enabled", llmEnabled);
        llm.put("configuredProvider", configuredLlmProvider);
        llm.put("effectiveProvider", llmHealth.backend() != null ? llmHealth.backend() : configuredLlmProvider);
        llm.put("configuredModel", configuredLlmModelId);
        llm.put("configuredModelPath", configuredLlmModelPath);
        llm.put("effectiveModel", llmHealth.modelName());
        llm.put("effectiveModelPath", llmHealth.modelPath());
        llm.put("baseUrl", llmBaseUrl);
        llm.put("configuredDevicePath", configuredDevicePath);
        llm.put("configuredDeviceSetting", configuredDeviceSetting);
        llm.put("effectiveDevicePath", effectiveDevicePath);
        llm.put("configuredGpuLayers", configuredGpuLayers);
        llm.put("effectiveGpuLayers", effectiveGpuLayers);
        llm.put("llamaCppPackageSpec", configuredLlamaCppPackageSpec);
        llm.put("device", llmHealth.device());
        llm.put("gpuAvailable", llmHealth.gpuAvailable());
        llm.put("cudaVersion", llmHealth.cudaVersion());
        llm.put("available", llmAvailable);
        llm.put("status", llmHealth.status());
        llm.put("reason", llmEnabled ? blankToEmpty(llmHealth.error()) : "jarvis.llm.enabled=false");
        llm.put("diagnostics", llmDiagnostics);

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("configuredProvider", "sentence-transformers");
        embedding.put("effectiveProvider", "sentence-transformers");
        embedding.put("configuredModel", configuredEmbeddingModelId);
        embedding.put("configuredModelPath", configuredEmbeddingModelPath);
        embedding.put("effectiveModel", memoryHealth.embeddingModel());
        embedding.put("effectiveModelPath", memoryHealth.embeddingModel());
        embedding.put("dimension", memoryHealth.embeddingDimension());
        embedding.put("available", memoryRequired && memoryHealth.embeddingServiceUp());
        embedding.put("reason", memoryRequired ? blankToEmpty(memoryHealth.embeddingError()) : "memory disabled");

        Map<String, Object> vectorStore = new LinkedHashMap<>();
        vectorStore.put("configuredProvider", "postgresql+pgvector");
        vectorStore.put("effectiveProvider", "postgresql+pgvector");
        vectorStore.put("datasourceUrl", datasourceUrl);
        vectorStore.put("databaseUp", memoryRequired && memoryHealth.databaseUp());
        vectorStore.put("pgvectorAvailable", memoryRequired && memoryHealth.pgvectorAvailable());
        vectorStore.put("available", memoryRequired && memoryHealth.databaseUp() && memoryHealth.pgvectorAvailable());
        vectorStore.put("reason", memoryRequired ? vectorReason(memoryHealth) : "memory disabled");

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("enabled", memoryEnabled);
        memory.put("serviceEnabled", memoryServiceEnabled);
        memory.put("serviceUrl", memoryServiceUrl);
        memory.put("available", memoryAvailable);
        memory.put("status", memoryHealth.status());
        memory.put("reason", memoryRequired ? memoryReason(memoryHealth) : "memory disabled");

        Map<String, Object> localDefaultStack = new LinkedHashMap<>();
        localDefaultStack.put("id", canonicalLocalAiStack);
        localDefaultStack.put("llmProvider", "llamacpp");
        localDefaultStack.put("llmModel", configuredLlmModelId);
        localDefaultStack.put("llmModelPath", configuredLlmModelPath);
        localDefaultStack.put("embeddingProvider", "sentence-transformers");
        localDefaultStack.put("embeddingModel", configuredEmbeddingModelId);
        localDefaultStack.put("embeddingModelPath", configuredEmbeddingModelPath);
        localDefaultStack.put("vectorStore", "postgresql+pgvector");
        localDefaultStack.put("fullLocalAiReadiness", fullLocalAiReadiness);
        localDefaultStack.put("status", localStackStatus(llmEnabled, llmAvailable, memoryRequired, memoryAvailable));

        Map<String, Object> canonicalCpuBaseline = new LinkedHashMap<>();
        canonicalCpuBaseline.put("status", "verified");
        canonicalCpuBaseline.put("devicePath", "cpu");
        canonicalCpuBaseline.put("nGpuLayers", 0);
        canonicalCpuBaseline.put("provider", "llamacpp");
        canonicalCpuBaseline.put("model", configuredLlmModelId);
        canonicalCpuBaseline.put("modelPath", configuredLlmModelPath);
        canonicalCpuBaseline.put("smokeScript", "scripts/ai-local-smoke.sh");

        Map<String, Object> gpu = loadGpuReadiness(llmHealth.gpuAvailable());
        gpu.put("available", llmHealth.gpuAvailable());
        gpu.put("configuredDevicePath", configuredDevicePath);
        gpu.put("effectiveDevicePath", effectiveDevicePath);
        gpu.put("configuredGpuLayers", configuredGpuLayers);
        gpu.put("effectiveGpuLayers", effectiveGpuLayers);
        gpu.put("statusFile", gpuStatusFile);
        gpu.put("canonicalCpuBaseline", canonicalCpuBaseline);

        LlmLifecycleManager.State lifecycleState = lifecycleManager.getState();
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("state", lifecycleState.name());
        lifecycle.put("reason", lifecycleManager.getStateReason());
        lifecycle.put("warmup_complete", lifecycleManager.isWarmupComplete());
        lifecycle.put("usable", lifecycleManager.isUsable());
        lifecycle.put("last_state_change", lifecycleManager.getLastStateChange().toString());

        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("active_inferences", admissionController.getActiveInferences());
        admission.put("queue_depth", admissionController.getQueueDepth());
        admission.put("total_admitted", admissionController.getTotalAdmitted());
        admission.put("rejected_count", admissionController.getRejectedCount());
        admission.put("timeout_count", admissionController.getTimeoutCount());
        admission.put("available_permits", admissionController.getAvailablePermits());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("service", "llm-service");
        summary.put("status", topLevelStatus(llmEnabled, llmAvailable, memoryRequired, memoryAvailable));
        summary.put("fullLocalAiReadiness", fullLocalAiReadiness);
        summary.put("lifecycle", lifecycle);
        summary.put("admission", admission);
        summary.put("routing", routing);
        summary.put("maturity", maturity);
        summary.put("localDefaultStack", localDefaultStack);
        summary.put("llm", llm);
        summary.put("gpu", gpu);
        summary.put("embedding", embedding);
        summary.put("vectorStore", vectorStore);
        summary.put("memory", memory);
        return summary;
    }

    private String topLevelStatus(boolean llmEnabled, boolean llmAvailable, boolean memoryRequired, boolean memoryAvailable) {
        if (!llmEnabled) {
            return "disabled";
        }
        if (llmAvailable && (!memoryRequired || memoryAvailable)) {
            return memoryRequired ? "ready" : "llm-only";
        }
        if (llmAvailable) {
            return "partial";
        }
        return "degraded";
    }

    private String localStackStatus(boolean llmEnabled, boolean llmAvailable, boolean memoryRequired, boolean memoryAvailable) {
        if (!llmEnabled) {
            return "disabled";
        }
        if (llmAvailable && memoryRequired && memoryAvailable) {
            return "ready";
        }
        return "partial";
    }

    private String memoryReason(MemoryClient.MemoryServiceHealth memoryHealth) {
        if (memoryHealth.available()) {
            return "";
        }
        if (memoryHealth.error() != null && !memoryHealth.error().isBlank()) {
            return memoryHealth.error();
        }
        if (!memoryHealth.databaseUp()) {
            return "database unavailable";
        }
        if (!memoryHealth.pgvectorAvailable()) {
            return "pgvector unavailable";
        }
        if (!memoryHealth.embeddingServiceUp()) {
            return memoryHealth.embeddingError() != null && !memoryHealth.embeddingError().isBlank()
                    ? memoryHealth.embeddingError()
                    : "embedding-service unavailable";
        }
        return "";
    }

    private String vectorReason(MemoryClient.MemoryServiceHealth memoryHealth) {
        if (!memoryHealth.databaseUp()) {
            return "database unavailable";
        }
        if (!memoryHealth.pgvectorAvailable()) {
            return "pgvector unavailable";
        }
        return "";
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveConfiguredDevicePath() {
        if (configuredGpuLayers != null && configuredGpuLayers == 0) {
            return "cpu";
        }
        if (configuredDeviceSetting == null || configuredDeviceSetting.isBlank() || "auto".equalsIgnoreCase(configuredDeviceSetting)) {
            return configuredGpuLayers != null && configuredGpuLayers != 0 ? "cuda" : "cpu";
        }
        return configuredDeviceSetting;
    }

    private Map<String, Object> loadGpuReadiness(Boolean gpuAvailable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("readinessStatus", gpuAvailable != null && gpuAvailable ? "unknown" : "unavailable");
        payload.put("readinessReason", gpuAvailable != null && gpuAvailable
                ? "ai-gpu-smoke has not been run for the current llama.cpp runtime"
                : "GPU not detected by llm-server");
        payload.put("lastVerifiedAt", "");
        payload.put("profile", "");
        payload.put("llamaCppPythonVersion", "");
        payload.put("packageSpec", configuredLlamaCppPackageSpec);
        payload.put("driverVersion", "");
        payload.put("gpuName", "");
        payload.put("verifiedDevicePath", "");
        payload.put("verifiedGpuLayers", null);

        if (gpuStatusFile == null || gpuStatusFile.isBlank()) {
            return payload;
        }

        Path statusPath = Path.of(gpuStatusFile);
        if (!Files.exists(statusPath)) {
            return payload;
        }

        try {
            Map<String, Object> filePayload = OBJECT_MAPPER.readValue(
                    Files.readString(statusPath),
                    new TypeReference<>() {
                    }
            );

            String status = stringValue(filePayload.get("status"));
            String reason = stringValue(filePayload.get("reason"));
            String packageSpec = stringValue(filePayload.get("packageSpec"));
            String modelPath = stringValue(filePayload.get("modelPath"));

            if (packageSpec != null && !packageSpec.equals(configuredLlamaCppPackageSpec)) {
                status = "stale";
                reason = "gpu verification was recorded for " + packageSpec
                        + " but runtime is configured for " + configuredLlamaCppPackageSpec;
            } else if (modelPath != null && !modelPath.equals(configuredLlmModelPath)) {
                status = "stale";
                reason = "gpu verification was recorded for a different GGUF model path";
            } else if (gpuAvailable != null && !gpuAvailable) {
                status = "unavailable";
                reason = "GPU not detected by llm-server";
            }

            payload.put("readinessStatus", status != null ? status : payload.get("readinessStatus"));
            payload.put("readinessReason", reason != null ? reason : payload.get("readinessReason"));
            payload.put("lastVerifiedAt", stringValue(filePayload.get("verifiedAt")));
            payload.put("profile", stringValue(filePayload.get("profile")));
            payload.put("llamaCppPythonVersion", stringValue(filePayload.get("llamaCppPythonVersion")));
            payload.put("packageSpec", packageSpec != null ? packageSpec : configuredLlamaCppPackageSpec);
            payload.put("driverVersion", stringValue(filePayload.get("driverVersion")));
            payload.put("gpuName", stringValue(filePayload.get("gpuName")));
            payload.put("verifiedDevicePath", stringValue(filePayload.get("effectiveDevicePath")));
            payload.put("verifiedGpuLayers", integerValue(filePayload.get("effectiveGpuLayers")));
            return payload;
        } catch (IOException exception) {
            payload.put("readinessStatus", "error");
            payload.put("readinessReason", "failed to parse gpu readiness file: " + exception.getMessage());
            return payload;
        }
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }
}
