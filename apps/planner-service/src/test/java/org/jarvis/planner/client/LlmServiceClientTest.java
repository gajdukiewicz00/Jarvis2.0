package org.jarvis.planner.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmServiceClientTest {

    @Test
    void enhancementPathIsExplicitlyUnsupportedInPlannerService() {
        LlmServiceClient client = new LlmServiceClient(new RestTemplate(), "http://llm-service:8080");

        assertThrows(UnsupportedOperationException.class,
                () -> client.enhancePlanDescription("user-1", "plan"));
    }
}
