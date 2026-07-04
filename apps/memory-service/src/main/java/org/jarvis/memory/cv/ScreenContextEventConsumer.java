package org.jarvis.memory.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code jarvis.cv.screen_context.created} and projects each event
 * into {@code screen_context_observation} via
 * {@link ScreenContextPersistenceService}.
 *
 * <p>Malformed payloads are logged at WARN and acked so the partition never
 * blocks. Persistence is idempotent, so a redelivery after a transient error
 * is safe.</p>
 *
 * <p>Disabled by setting {@code jarvis.memory.cv.enabled=false}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "jarvis.memory.cv", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ScreenContextEventConsumer {

    private final ScreenContextPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${jarvis.memory.cv.topic:jarvis.cv.screen_context.created}",
            groupId = "${jarvis.memory.cv.consumer-group:cv-screen-context-projector}")
    public void onScreenContext(@Payload String json,
                                @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        ScreenContextEvent event;
        try {
            event = objectMapper.readValue(json, ScreenContextEvent.class);
        } catch (Exception ex) {
            log.warn("screen-context consumer got malformed event (key={}): {}", key, ex.getMessage());
            return;
        }
        persistenceService.persist(event);
    }
}
