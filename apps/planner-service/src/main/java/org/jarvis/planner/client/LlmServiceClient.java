package org.jarvis.planner.client;

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
 * Optional adapter to llm-service. Planner core must stay functional without it.
 *
 * <p>Delegates all generative work to llm-service's /api/v1/llm/chat (which runs
 * on the host GPU Qwen3-14B). The shared {@code restTemplate} attaches a
 * SVC_INTERNAL service token so the call is accepted as internal.</p>
 */
@Slf4j
@Component
public class LlmServiceClient {

    private final RestTemplate restTemplate;
    private final String llmServiceUrl;

    public LlmServiceClient(
            RestTemplate restTemplate,
            @Value("${services.llm-service.url:http://llm-service:8091}") String llmServiceUrl) {
        this.restTemplate = restTemplate;
        this.llmServiceUrl = llmServiceUrl;
    }

    public boolean isHealthy() {
        try {
            restTemplate.getForEntity(llmServiceUrl + "/api/v1/llm/health", String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("llm-service health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Low-level single-turn completion against llm-service. Returns the reply text. */
    private String chat(String userId, String prompt) {
        String url = llmServiceUrl + "/api/v1/llm/chat";
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", "planner-" + (userId == null || userId.isBlank() ? "anon" : userId));
        body.put("messages", List.of(message));
        body.put("maxTokens", 512);
        body.put("temperature", 0.3);
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, body, Map.class);
            Object reply = resp.getBody() == null ? null : resp.getBody().get("reply");
            return reply == null ? "" : reply.toString().trim();
        } catch (RestClientException e) {
            log.warn("llm-service chat failed: {}", e.getMessage());
            throw new LlmUnavailableException("llm-service call failed: " + e.getMessage(), e);
        }
    }

    public String enhancePlanDescription(String userId, String planText) {
        return chat(userId, "Перепиши план дня кратко, по-человечески и мотивирующе, "
                + "сохрани все пункты и время. Верни только текст плана:\n\n" + planText);
    }

    public String generateDocument(String userId, String documentType, String context) {
        return chat(userId, "Сгенерируй документ типа '" + documentType + "' на основе контекста. "
                + "Пиши структурированно и по делу. Контекст:\n\n" + context);
    }

    public String parseTask(String userId, String naturalLanguage) {
        return chat(userId, "Извлеки задачу из фразы и верни СТРОГО JSON без пояснений: "
                + "{\"title\": <кратко>, \"priority\": \"LOW|MEDIUM|HIGH\", \"dueHint\": <текст срока или null>}. "
                + "Фраза: " + naturalLanguage);
    }

    public String recommend(String userId, String context) {
        return chat(userId, "Дай одну конкретную, выполнимую рекомендацию по планированию дня "
                + "на основе контекста (1-2 предложения). Контекст:\n\n" + context);
    }

    /** Thrown when llm-service is unreachable; callers may degrade gracefully. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
