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
import java.util.Locale;
import java.util.Map;

/**
 * Local-only adapter for a llama.cpp server (the official
 * {@code llama-server} or compatible) exposing the OpenAI vision-style
 * {@code /v1/chat/completions} endpoint. Endpoint must stay on a
 * loopback / private-network address.
 *
 * <p>Wire format:
 * <pre>
 * POST {endpoint}/v1/chat/completions
 * { "model": "...", "stream": false, "temperature": 0.2,
 *   "max_tokens": 512,
 *   "messages": [ { "role": "user", "content": [
 *     { "type": "text", "text": "..." },
 *     { "type": "image_url",
 *       "image_url": { "url": "data:image/png;base64,..." } } ] } ] }
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'${vision-security.cv.vlm.enabled:false}'.equals('true') "
                + "&& '${vision-security.cv.vlm.provider:disabled}'.equalsIgnoreCase('llamacpp')")
public class LlamaCppLocalVlmAdapter implements LocalVlmAdapter {

    public static final String ID = "llamacpp";

    private final VisionSecurityProperties.Vlm config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public LlamaCppLocalVlmAdapter(VisionSecurityProperties properties) {
        this(properties.getCv().getVlm(), defaultHttpClient(properties.getCv().getVlm().getTimeout()));
    }

    public LlamaCppLocalVlmAdapter(VisionSecurityProperties.Vlm config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
        OllamaLocalVlmAdapter.guardLocalOnly(config.getEndpoint());
    }

    @Override public String id() { return ID; }
    @Override public String model() { return config.getModel(); }

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
        return invoke(OllamaLocalVlmAdapter.buildPrompt(question, context, config.isIncludeOcrContext()),
                imagePath);
    }

    private VlmResult invoke(String prompt, Path imagePath) {
        long startNs = System.nanoTime();
        if (config.getEndpoint() == null || config.getEndpoint().isBlank()) {
            return VlmResult.unavailable(ID, "vlm.endpoint is empty", 0L);
        }
        // When include-screenshot is off, run text-only over the OCR context.
        boolean attachImage = config.isIncludeScreenshot();
        List<Map<String, Object>> userContent = new java.util.ArrayList<>();
        userContent.add(Map.of("type", "text", "text", prompt));
        if (attachImage) {
            if (imagePath == null) {
                return VlmResult.unavailable(ID, "no image to send to VLM", 0L);
            }
            if (!Files.isRegularFile(imagePath)) {
                return VlmResult.unavailable(ID, "image file not found: " + imagePath, 0L);
            }
            String base64;
            try {
                base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            } catch (Exception ex) {
                return VlmResult.unavailable(ID,
                        "failed to read image " + imagePath + ": " + ex.getMessage(), 0L);
            }
            String mime = guessMimeType(imagePath);
            userContent.add(Map.of("type", "image_url",
                    "image_url", Map.of("url", "data:" + mime + ";base64," + base64)));
        }

        Map<String, Object> body = Map.of(
                "model", config.getModel() == null ? "" : config.getModel(),
                "stream", false,
                "temperature", config.getTemperature(),
                "max_tokens", config.getMaxTokens(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", userContent)));
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception ex) {
            return VlmResult.unavailable(ID, "request serialisation failed: " + ex.getMessage(),
                    durationMs(startNs));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(OllamaLocalVlmAdapter.stripTrailingSlash(config.getEndpoint())
                            + "/v1/chat/completions"))
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
                        "llama-server returned HTTP " + response.statusCode() + ": "
                                + OllamaLocalVlmAdapter.truncate(response.body(), 240),
                        durationMs(startNs));
            }
            JsonNode parsed = mapper.readTree(response.body());
            JsonNode choices = parsed.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return VlmResult.unavailable(ID,
                        "llama-server response had no choices[]", durationMs(startNs));
            }
            String content = choices.get(0).path("message").path("content").asText("").trim();
            if (content.isEmpty()) {
                return VlmResult.unavailable(ID,
                        "llama-server returned empty message.content", durationMs(startNs));
            }
            return VlmResult.success(ID, content, durationMs(startNs));
        } catch (java.net.http.HttpTimeoutException ex) {
            return VlmResult.unavailable(ID,
                    "llama-server call timed out after " + config.getTimeout(),
                    durationMs(startNs));
        } catch (java.net.ConnectException ex) {
            return VlmResult.unavailable(ID,
                    "cannot connect to local llama-server at " + config.getEndpoint()
                            + ": " + ex.getMessage(),
                    durationMs(startNs));
        } catch (Exception ex) {
            return VlmResult.unavailable(ID,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    durationMs(startNs));
        }
    }

    static String guessMimeType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private static long durationMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static HttpClient defaultHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout == null ? Duration.ofSeconds(5) : timeout)
                .build();
    }
}
