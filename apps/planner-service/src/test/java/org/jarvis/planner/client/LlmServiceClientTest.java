package org.jarvis.planner.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmServiceClientTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void enhancePlanDescriptionPostsToLlmServiceAndReturnsReply() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of("reply", "Enhanced plan")));

        LlmServiceClient client = new LlmServiceClient(rt, "http://llm-service:8091");

        assertEquals("Enhanced plan", client.enhancePlanDescription("user-1", "plan"));
    }
}
