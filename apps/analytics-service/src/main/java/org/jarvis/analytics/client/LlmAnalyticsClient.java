package org.jarvis.analytics.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional adapter to llm-service for the NL-analytics endpoint. Analytics core
 * must stay functional without it — see {@link org.jarvis.analytics.service.NlAnalyticsService}.
 *
 * <p>Delegates to llm-service's {@code /api/v1/llm/chat} (host GPU Qwen3-14B).
 * The shared {@code restTemplate} attaches a SVC_INTERNAL service token so the
 * call is accepted as internal (see {@code LlmRestClientConfig}).</p>
 */
@Slf4j
@Component
public class LlmAnalyticsClient {

    private static final int MAX_TOKENS = 400;
    private static final double TEMPERATURE = 0.2;

    private final RestTemplate restTemplate;
    private final String llmServiceUrl;

    public LlmAnalyticsClient(
            RestTemplate restTemplate,
            @Value("${jarvis.llm-service.url:http://llm-service:8091}") String llmServiceUrl) {
        this.restTemplate = restTemplate;
        this.llmServiceUrl = llmServiceUrl;
    }

    /** Routes a natural-language analytics question to llm-service, seeded with a short rule-based context summary. */
    public String ask(String userId, String question, String contextSummary) {
        String url = llmServiceUrl + "/api/v1/llm/chat";
        String prompt = "Ты аналитик личной продуктивности. На основе контекста ответь кратко и по делу "
                + "на вопрос пользователя. Контекст: " + contextSummary + "\n\nВопрос: " + question;
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", "analytics-" + (userId == null || userId.isBlank() ? "anon" : userId));
        body.put("messages", List.of(message));
        body.put("maxTokens", MAX_TOKENS);
        body.put("temperature", TEMPERATURE);
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, body, Map.class);
            Object reply = resp.getBody() == null ? null : resp.getBody().get("reply");
            return reply == null ? "" : reply.toString().trim();
        } catch (RestClientException e) {
            log.warn("llm-service NL-analytics call failed: {}", e.getMessage());
            throw new LlmUnavailableException("llm-service call failed: " + e.getMessage(), e);
        }
    }

    /** Thrown when llm-service is unreachable; callers may degrade gracefully. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
