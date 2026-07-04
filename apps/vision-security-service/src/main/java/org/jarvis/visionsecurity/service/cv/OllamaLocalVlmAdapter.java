package org.jarvis.visionsecurity.service.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Local-only adapter that talks to an Ollama daemon over HTTP. Endpoint
 * must point at a loopback / private-network address — Jarvis policy
 * forbids cloud vision APIs.
 *
 * <p>Wire format follows the documented Ollama generate API:
 * {@code POST {endpoint}/api/generate}
 * with body
 * <pre>
 * { "model": "llava", "prompt": "...",
 *   "images": ["&lt;base64-png&gt;"], "stream": false,
 *   "options": { "temperature": 0.2, "num_predict": 512 } }
 * </pre>
 * The response field {@code response} carries the model text.</p>
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'${vision-security.cv.vlm.enabled:false}'.equals('true') "
                + "&& '${vision-security.cv.vlm.provider:disabled}'.equalsIgnoreCase('ollama')")
public class OllamaLocalVlmAdapter implements LocalVlmAdapter {

    public static final String ID = "ollama";

    private final VisionSecurityProperties.Vlm config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public OllamaLocalVlmAdapter(VisionSecurityProperties properties) {
        this(properties.getCv().getVlm(), defaultHttpClient(properties.getCv().getVlm().getTimeout()));
    }

    /** Test-friendly constructor: inject custom config + HttpClient. */
    public OllamaLocalVlmAdapter(VisionSecurityProperties.Vlm config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
        guardLocalOnly(config.getEndpoint());
    }

    @Override
    public String id() { return ID; }

    @Override
    public String model() { return config.getModel(); }

    @Override
    public VlmResult summarise(CvAnalysisResult context) {
        String prompt = "Describe what is happening on this screen in 2-3 sentences. "
                + "Be concrete, name the application or page if you can see it.";
        Path imagePath = context == null || context.imagePath() == null
                ? null
                : Path.of(context.imagePath());
        return invoke(prompt, imagePath);
    }

    @Override
    public VlmResult answer(String question, Path imagePath, CvAnalysisResult context) {
        String prompt = buildPrompt(question, context, config.isIncludeOcrContext());
        return invoke(prompt, imagePath);
    }

    private VlmResult invoke(String prompt, Path imagePath) {
        long startNs = System.nanoTime();
        if (config.getEndpoint() == null || config.getEndpoint().isBlank()) {
            return VlmResult.unavailable(ID, "vlm.endpoint is empty", 0L);
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            return VlmResult.unavailable(ID, "vlm.model is empty", 0L);
        }
        // When include-screenshot is off, run text-only over the OCR context.
        boolean attachImage = config.isIncludeScreenshot();
        List<String> images = List.of();
        if (attachImage) {
            if (imagePath == null) {
                return VlmResult.unavailable(ID, "no image to send to VLM", 0L);
            }
            if (!Files.isRegularFile(imagePath)) {
                return VlmResult.unavailable(ID,
                        "image file not found: " + imagePath, 0L);
            }
            try {
                images = List.of(Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath)));
            } catch (Exception ex) {
                return VlmResult.unavailable(ID,
                        "failed to read image " + imagePath + ": " + ex.getMessage(), 0L);
            }
        }

        String json;
        try {
            json = mapper.writeValueAsString(Map.of(
                    "model", config.getModel(),
                    "prompt", prompt,
                    "images", images,
                    "stream", false,
                    "options", Map.of(
                            "temperature", config.getTemperature(),
                            "num_predict", config.getMaxTokens())));
        } catch (Exception ex) {
            return VlmResult.unavailable(ID, "request serialisation failed: " + ex.getMessage(),
                    durationMs(startNs));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(config.getEndpoint()) + "/api/generate"))
                    .timeout(config.getTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (IllegalArgumentException ex) {
            return VlmResult.unavailable(ID,
                    "invalid vlm.endpoint '" + config.getEndpoint() + "': " + ex.getMessage(),
                    durationMs(startNs));
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return VlmResult.unavailable(ID,
                        "ollama returned HTTP " + response.statusCode() + ": "
                                + truncate(response.body(), 240),
                        durationMs(startNs));
            }
            JsonNode parsed = mapper.readTree(response.body());
            String content = parsed.path("response").asText("").trim();
            if (content.isEmpty()) {
                return VlmResult.unavailable(ID,
                        "ollama returned empty 'response' field", durationMs(startNs));
            }
            return VlmResult.success(ID, content, durationMs(startNs));
        } catch (java.net.http.HttpTimeoutException ex) {
            return VlmResult.unavailable(ID,
                    "ollama call timed out after " + config.getTimeout(),
                    durationMs(startNs));
        } catch (java.net.ConnectException ex) {
            return VlmResult.unavailable(ID,
                    "cannot connect to local Ollama at " + config.getEndpoint() + ": " + ex.getMessage(),
                    durationMs(startNs));
        } catch (Exception ex) {
            return VlmResult.unavailable(ID,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    durationMs(startNs));
        }
    }

    static String buildPrompt(String question, CvAnalysisResult context, boolean includeOcr) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a local screen-understanding assistant. ")
                .append("Answer the user's question using only the screenshot ")
                .append("and the OCR text below. If the screen does not give ")
                .append("an answer, say so plainly.\n\n");
        if (includeOcr && context != null && context.ocrText() != null && !context.ocrText().isBlank()) {
            sb.append("OCR text:\n")
                    .append(truncate(context.ocrText(), 3000))
                    .append("\n\n");
        }
        sb.append("Question: ").append(question == null ? "" : question.trim());
        return sb.toString();
    }

    static String stripTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static long durationMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static HttpClient defaultHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout == null ? Duration.ofSeconds(5) : timeout)
                .build();
    }

    static void guardLocalOnly(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        String lower = endpoint.toLowerCase();
        // Reject obvious cloud endpoints. The list is short but catches
        // accidental copy-paste mistakes; the actual policy is local-only,
        // and this is a defensive belt-and-braces check.
        String[] banned = {
                "api.openai.com", "openai.com",
                "generativelanguage.googleapis.com", "googleapis.com",
                "anthropic.com",
                "azure.com", "amazonaws.com",
                "huggingface.co", "replicate.com"
        };
        for (String host : banned) {
            if (lower.contains(host)) {
                throw new IllegalArgumentException(
                        "vision-security.cv.vlm.endpoint points at a non-local host '"
                                + endpoint + "'. Jarvis CV must stay local-only — "
                                + "use Ollama / llama.cpp on a loopback/private address.");
            }
        }
    }
}
