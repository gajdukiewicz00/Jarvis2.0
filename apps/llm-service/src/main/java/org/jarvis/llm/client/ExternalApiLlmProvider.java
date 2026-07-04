package org.jarvis.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional external OpenAI-compatible LLM provider, selected with
 * {@code llm.provider=external}. DISABLED by default. The API key is read from
 * the environment ({@code LLM_EXTERNAL_API_KEY}) and is NEVER logged.
 *
 * <p>Privacy note (EPIC 3/5): sensitive/local-only content must not be routed
 * here — that gating is enforced by the caller's privacy policy, not this
 * transport.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "external")
public class ExternalApiLlmProvider implements LlmProvider {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public ExternalApiLlmProvider(
            RestTemplateBuilder builder,
            @Value("${llm.external.base-url:}") String baseUrl,
            @Value("${llm.external.model:gpt-4o-mini}") String model,
            @Value("${LLM_EXTERNAL_API_KEY:}") String apiKey) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        log.info("ExternalApiLlmProvider initialized: baseUrl={}, model={}, apiKeyPresent={}",
                baseUrl.isBlank() ? "<unset>" : baseUrl, model, !apiKey.isBlank());
    }

    @Override
    public String providerName() {
        return "external";
    }

    @Override
    public boolean isHealthy() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public ChatResponseDto chat(List<ChatMessageDto> messages, Integer maxTokens, Double temperature,
            String correlationId) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw new LlmClient.LlmClientException(
                    "External LLM provider is selected but llm.external.base-url / LLM_EXTERNAL_API_KEY are not set", null);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.getRole().name().toLowerCase(),
                        "content", m.getContent() == null ? "" : m.getContent()))
                .toList());
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey); // sent only as Authorization header; never logged
        long start = System.currentTimeMillis();
        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(
                    baseUrl + "/v1/chat/completions", new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientResponseException e) {
            // Fail safely: surface a clear exception without leaking the API key.
            throw new LlmClient.LlmClientException(
                    "External LLM HTTP error: " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            throw new LlmClient.LlmClientException("External LLM request failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        String reply = extractContent(resp.getBody());
        if (reply == null) {
            throw new LlmClient.LlmClientException("External LLM response missing choices[0].message.content", null);
        }
        log.info("[{}] external LLM chat <- {} in {}ms, reply.length={}",
                correlationId, resp.getStatusCode().value(), elapsed, reply.length());
        return new ChatResponseDto(reply, null, model, (int) Math.min(elapsed, Integer.MAX_VALUE), null);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> msg && msg.get("content") != null) {
                return msg.get("content").toString();
            }
        }
        return null;
    }
}
