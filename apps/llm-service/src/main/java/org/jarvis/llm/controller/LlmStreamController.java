package org.jarvis.llm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatRequestDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Token-by-token streaming chat over Server-Sent Events. Proxies the local
 * llama.cpp daemon's {@code stream:true} SSE and forwards each delta to the
 * client, so the UI can render the answer as it is generated.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
public class LlmStreamController {

    private static final String PERSONA =
            "Ты — J.A.R.V.I.S., вежливый британский ИИ-ассистент. Отвечай на языке "
            + "пользователя, лаконично и по делу, с лёгкой иронией. /no_think";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.base-url:http://localhost:5000}")
    private String daemonUrl;

    @Value("${llm.stream.timeout-seconds:120}")
    private long timeoutSeconds;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(timeoutSeconds * 2).toMillis());
        String body = buildDaemonBody(request);

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(daemonUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<java.util.stream.Stream<String>> resp =
                        http.send(req, HttpResponse.BodyHandlers.ofLines());
                resp.body().forEach(line -> forwardLine(emitter, line));
                emitter.complete();
            } catch (Exception e) {
                log.warn("stream failed: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void forwardLine(SseEmitter emitter, String line) {
        if (line == null || !line.startsWith("data: ")) {
            return;
        }
        String data = line.substring("data: ".length()).trim();
        if (data.equals("[DONE]")) {
            return;
        }
        String token = extractContent(data);
        if (token != null && !token.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().data(token));
            } catch (Exception e) {
                throw new RuntimeException("client disconnected", e);
            }
        }
    }

    private String extractContent(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            return content.isMissingNode() || content.isNull() ? null : content.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildDaemonBody(ChatRequestDto request) {
        StringBuilder messages = new StringBuilder();
        messages.append("{\"role\":\"system\",\"content\":").append(jsonString(PERSONA)).append('}');
        if (request != null && request.getMessages() != null) {
            request.getMessages().forEach(m -> messages.append(",{\"role\":")
                    .append(jsonString(m.getRole() == null ? "user" : m.getRole().toJson()))
                    .append(",\"content\":")
                    .append(jsonString(m.getContent() == null ? "" : m.getContent()))
                    .append('}'));
        }
        return "{\"stream\":true,\"temperature\":0.5,\"max_tokens\":512,\"messages\":[" + messages + "]}";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
