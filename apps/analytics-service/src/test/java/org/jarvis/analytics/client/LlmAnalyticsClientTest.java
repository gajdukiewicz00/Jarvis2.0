package org.jarvis.analytics.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmAnalyticsClientTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void askPostsToLlmServiceAndReturnsReply() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of("reply", "Ты мало спал на этой неделе.")));

        LlmAnalyticsClient client = new LlmAnalyticsClient(rt, "http://llm-service:8091");

        assertEquals("Ты мало спал на этой неделе.",
                client.ask("user-1", "Почему я устал?", "контекст"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void askThrowsLlmUnavailableExceptionWhenRestCallFails() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        LlmAnalyticsClient client = new LlmAnalyticsClient(rt, "http://llm-service:8091");

        assertThrows(LlmAnalyticsClient.LlmUnavailableException.class,
                () -> client.ask("user-1", "question", "context"));
    }
}
