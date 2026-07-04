package org.jarvis.llm.client;

import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MemoryClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ServiceJwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        jwtProvider = mock(ServiceJwtProvider.class);
        when(jwtProvider.createToken(anyString(), anyList())).thenReturn("svc-token");
    }

    private MemoryClient client(boolean enabled) {
        return new MemoryClient(restTemplate, "http://memory-service:8093", enabled, jwtProvider, "llm-service");
    }

    @Test
    void searchContextReturnsDisabledResultWhenServiceDisabled() {
        MemoryClient client = client(false);

        MemoryClient.SearchContextResult result =
                client.searchContext("user1", "query", 5, 600, true, true, "corr-1");

        assertThat(result.contextText()).isEmpty();
        assertThat(result.retrievalMode()).isEqualTo("disabled");
        assertThat(result.degradedReason()).isEqualTo("memory.service.enabled=false");
    }

    @Test
    void ingestAsyncNoOpWhenDisabled() {
        MemoryClient client = client(false);

        client.ingestAsync("user1", "session1", "hi", "hello", "corr-1");
        // no request should be attempted at all - nothing to verify() against, but no exception either
    }

    @Test
    void searchContextSuccessReturnsContextText() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "contextText": "relevant memory",
                          "retrievalMode": "semantic",
                          "estimatedTokens": 42,
                          "chunks": [ {"id": "1", "text": "a", "similarity": 0.9} ]
                        }
                        """, MediaType.APPLICATION_JSON));

        MemoryClient.SearchContextResult result =
                client.searchContext("user1", "query", 5, 600, true, false, "corr-2");

        assertThat(result.contextText()).isEqualTo("relevant memory");
        assertThat(result.retrievalMode()).isEqualTo("semantic");
        assertThat(result.estimatedTokens()).isEqualTo(42);
        assertThat(result.chunkCount()).isEqualTo(1);
        server.verify();
    }

    @Test
    void searchContextThrowsWhenResponseBodyEmpty() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/search"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.searchContext("user1", "query", 5, 600, true, true, "corr-3"))
                .isInstanceOf(MemoryClient.MemoryClientException.class);
    }

    @Test
    void searchContextWrapsExceptionOnServerError() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/search"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.searchContext("user1", "query", 5, 600, true, true, "corr-4"))
                .isInstanceOf(MemoryClient.MemoryClientException.class)
                .hasMessageContaining("Memory search failed");
    }

    @Test
    void ingestAsyncSendsRequestWhenEnabled() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/ingest/async"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.ingestAsync("user1", "session1", "hi", "hello there", "corr-5");

        server.verify();
    }

    @Test
    void ingestAsyncThrowsWrappedExceptionOnFailure() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/ingest/async"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.ingestAsync("user1", "session1", "hi", "hello", "corr-6"))
                .isInstanceOf(MemoryClient.MemoryClientException.class)
                .hasMessageContaining("Memory ingest failed");
    }

    @Test
    void getHealthReturnsDisabledWhenServiceDisabled() {
        MemoryClient client = client(false);

        MemoryClient.MemoryServiceHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("disabled");
        assertThat(health.error()).isEqualTo("memory.service.enabled=false");
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void getHealthParsesHealthyResponse() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status": "healthy",
                          "database": "up",
                          "pgvector": "available",
                          "embeddingService": "up",
                          "embeddingModel": "bge-small",
                          "embeddingDimension": 384
                        }
                        """, MediaType.APPLICATION_JSON));

        MemoryClient.MemoryServiceHealth health = client.getHealth();

        assertThat(health.available()).isTrue();
        assertThat(health.databaseUp()).isTrue();
        assertThat(health.pgvectorAvailable()).isTrue();
        assertThat(health.embeddingServiceUp()).isTrue();
        assertThat(health.embeddingModel()).isEqualTo("bge-small");
        assertThat(health.embeddingDimension()).isEqualTo(384);
        server.verify();
    }

    @Test
    void getHealthReturnsUnhealthyWhenStatusNotHealthy() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/health"))
                .andRespond(withSuccess("""
                        { "status": "degraded" }
                        """, MediaType.APPLICATION_JSON));

        MemoryClient.MemoryServiceHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("degraded");
    }

    @Test
    void getHealthReturnsErrorOnException() {
        MemoryClient client = client(true);

        server.expect(requestTo("http://memory-service:8093/memory/health"))
                .andRespond(withServerError());

        MemoryClient.MemoryServiceHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("error");
        assertThat(health.error()).isNotBlank();
    }
}
