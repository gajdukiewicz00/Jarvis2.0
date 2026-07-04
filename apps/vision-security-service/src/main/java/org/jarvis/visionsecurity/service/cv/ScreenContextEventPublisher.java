package org.jarvis.visionsecurity.service.cv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes screen-context observations onto the
 * {@code jarvis.cv.screen_context.created} Kafka topic so downstream
 * consumers (memory-service, planner-service, …) can subscribe.
 *
 * <p>Failure-tolerant: when Kafka is not configured (no
 * {@code KafkaTemplate} bean / no {@code spring.kafka.bootstrap-servers})
 * publishing is a logged no-op. Producers must never block CV
 * processing on the bus.</p>
 */
@Slf4j
@Component
public class ScreenContextEventPublisher {

    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final VisionSecurityProperties properties;
    private final ObjectMapper mapper;

    public ScreenContextEventPublisher(ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
                                       VisionSecurityProperties properties) {
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.properties = properties;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * @return {@code true} when the event was handed to Kafka,
     *         {@code false} when publishing was skipped (disabled or no
     *         template available).
     */
    public boolean publish(ScreenContextResult result) {
        if (result == null) return false;
        VisionSecurityProperties.Cv cv = properties.getCv();
        if (!cv.isPublishScreenContextEvent()) {
            log.debug("screen-context publishing disabled by config");
            return false;
        }
        KafkaTemplate<String, String> template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            log.debug("KafkaTemplate not available; skipping screen-context publish");
            return false;
        }
        String topic = cv.getScreenContextTopic();
        String key = result.userId() != null && !result.userId().isBlank()
                ? result.userId()
                : "anonymous";
        try {
            String json = mapper.writeValueAsString(result);
            template.send(topic, key, json);
            log.info("published screen-context event topic={} user={} blocks={} chars={} engine={} durationMs={}",
                    topic, key,
                    result.analysis() == null ? 0 : result.analysis().blocks().size(),
                    result.analysis() == null || result.analysis().ocrText() == null
                            ? 0 : result.analysis().ocrText().length(),
                    result.analysis() == null ? "(none)" : result.analysis().engine(),
                    result.analysis() == null ? -1 : result.analysis().durationMs());
            return true;
        } catch (JsonProcessingException ex) {
            log.warn("screen-context publish JSON failure: {}", ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("screen-context publish failed on topic {}: {}", topic, ex.getMessage());
            return false;
        }
    }
}
