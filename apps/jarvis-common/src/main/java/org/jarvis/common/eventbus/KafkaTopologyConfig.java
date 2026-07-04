package org.jarvis.common.eventbus;

import org.apache.kafka.clients.admin.NewTopic;
import org.jarvis.events.EventTopics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

/**
 * Phase 8 — declares the seven Kafka topics from SPEC-1 § "Messaging Split".
 *
 * <p>Auto-loaded only when {@code spring-kafka} is on the classpath AND
 * {@code spring.kafka.bootstrap-servers} is configured. Each topic gets
 * 3 partitions, 1 replica (MicroK8s baseline), 30-day retention, and
 * delete cleanup policy. Phase 12 may bump replication when Kafka grows
 * to multi-broker.</p>
 */
@AutoConfiguration
@ConditionalOnClass({KafkaTemplate.class})
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class KafkaTopologyConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .configs(Map.of(
                        "retention.ms", Long.toString(RETENTION_MS),
                        "cleanup.policy", "delete"
                ))
                .build();
    }

    @Bean public NewTopic jarvisVoiceEvents()            { return topic(EventTopics.VOICE); }
    @Bean public NewTopic jarvisDesktopActivityEvents()  { return topic(EventTopics.DESKTOP_ACTIVITY); }
    @Bean public NewTopic jarvisVisionEvents()           { return topic(EventTopics.VISION); }
    @Bean public NewTopic jarvisAuditEvents()            { return topic(EventTopics.AUDIT); }
    @Bean public NewTopic jarvisLifeEvents()             { return topic(EventTopics.LIFE); }
    @Bean public NewTopic jarvisMemoryEvents()           { return topic(EventTopics.MEMORY); }
    @Bean public NewTopic jarvisAnalyticsEvents()        { return topic(EventTopics.ANALYTICS); }
}
