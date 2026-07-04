package org.jarvis.common.eventbus;

import org.apache.kafka.clients.admin.NewTopic;
import org.jarvis.events.EventTopics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaTopologyConfigTest {

    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private final KafkaTopologyConfig config = new KafkaTopologyConfig();

    @Test
    void everyTopicHasThreePartitionsOneReplicaAndThirtyDayRetention() {
        assertTopicConfigured(config.jarvisVoiceEvents(), EventTopics.VOICE);
        assertTopicConfigured(config.jarvisDesktopActivityEvents(), EventTopics.DESKTOP_ACTIVITY);
        assertTopicConfigured(config.jarvisVisionEvents(), EventTopics.VISION);
        assertTopicConfigured(config.jarvisAuditEvents(), EventTopics.AUDIT);
        assertTopicConfigured(config.jarvisLifeEvents(), EventTopics.LIFE);
        assertTopicConfigured(config.jarvisMemoryEvents(), EventTopics.MEMORY);
        assertTopicConfigured(config.jarvisAnalyticsEvents(), EventTopics.ANALYTICS);
    }

    private void assertTopicConfigured(NewTopic topic, String expectedName) {
        assertEquals(expectedName, topic.name());
        assertEquals(3, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
        assertEquals(Long.toString(RETENTION_MS), topic.configs().get("retention.ms"));
        assertEquals("delete", topic.configs().get("cleanup.policy"));
    }
}
