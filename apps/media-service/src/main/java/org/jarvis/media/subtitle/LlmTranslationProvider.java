package org.jarvis.media.subtitle;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes translation through llm-service's {@code /api/v1/llm/chat}. Active only
 * when {@code media.translation.mode=llm}; {@link MockTranslationProvider} remains
 * the default.
 *
 * <p>{@code SubtitleService} already neutralizes each segment via {@link
 * MediaTextGuard#neutralize(String)} before calling {@link #translate}. This
 * provider wraps that already-neutralized text in the explicit UNTRUSTED_DATA
 * envelope again (idempotent — {@code wrap} re-neutralizes internally) before it
 * goes into the LLM prompt, so the injection-safety story holds even if a future
 * caller forgets the guard step. Media text is treated purely as DATA to
 * translate, never as instructions to the model.</p>
 *
 * <p>Fails closed: an unreachable llm-service or an empty reply raises {@link
 * TranslationException} rather than silently echoing untranslated text, matching
 * the failure posture of the other "real" providers in this module (ffmpeg,
 * ffprobe, whisper.cpp).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.translation.mode", havingValue = "llm")
public class LlmTranslationProvider implements TranslationProvider {

    private final RestTemplate restTemplate;
    private final MediaTextGuard guard;
    private final String llmServiceUrl;

    public LlmTranslationProvider(RestTemplate restTemplate, MediaTextGuard guard, MediaProperties props) {
        this.restTemplate = restTemplate;
        this.guard = guard;
        this.llmServiceUrl = props.translation().llmServiceUrl();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String translate(String neutralizedText, String targetLanguage) {
        if (neutralizedText == null || neutralizedText.isBlank()) {
            return "";
        }
        String language = (targetLanguage == null || targetLanguage.isBlank()) ? "ru" : targetLanguage;
        String wrapped = guard.wrap("media-transcript-segment", neutralizedText);
        String prompt = "Translate the DATA text below into " + language.toUpperCase(Locale.ROOT)
                + ". Reply with ONLY the translated line — no quotes, no commentary, no original text.\n\n"
                + wrapped;

        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", "media-translate");
        body.put("messages", List.of(message));
        body.put("maxTokens", 256);
        body.put("temperature", 0.0);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(llmServiceUrl + "/api/v1/llm/chat", body, Map.class);
            Object reply = response.getBody() == null ? null : response.getBody().get("reply");
            String text = reply == null ? "" : reply.toString().trim();
            if (text.isEmpty()) {
                throw new TranslationException("llm-service returned an empty translation");
            }
            return text;
        } catch (RestClientException e) {
            log.warn("llm-service translation failed: {}", e.getMessage());
            throw new TranslationException("llm-service call failed: " + e.getMessage(), e);
        }
    }
}
