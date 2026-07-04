package org.jarvis.memory.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EmbeddingClient} builds its own internal {@code WebClient} (no DI
 * seam), so these tests point it at a real, ephemeral-port
 * {@code com.sun.net.httpserver.HttpServer} instead of mocking — no extra
 * test dependency needed since it ships with the JDK.
 */
class EmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String path, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void embedReturnsVectorOnSuccess() throws IOException {
        String url = startServer("/embed/single", 200,
                "{\"embedding\":[0.1,0.2,0.3],\"model\":\"m\",\"dimension\":3,\"processing_time_ms\":5}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        List<Float> result = client.embed("hello world", "corr-1");

        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void embedThrowsWrappedExceptionWhenServiceReturnsErrorStatus() throws IOException {
        String url = startServer("/embed/single", 500, "{\"error\":\"boom\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        assertThatThrownBy(() -> client.embed("hello", "corr-2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to get embedding")
                .hasMessageContaining("500");
    }

    @Test
    void embedThrowsWhenResponseHasNoEmbeddingField() throws IOException {
        String url = startServer("/embed/single", 200, "{\"model\":\"m\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        assertThatThrownBy(() -> client.embed("hello", "corr-3"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void embedBatchReturnsEmbeddingsOnSuccess() throws IOException {
        String url = startServer("/embed", 200,
                "{\"embeddings\":[[0.1,0.2],[0.3,0.4]],\"model\":\"m\",\"dimension\":2,\"count\":2,\"processing_time_ms\":5}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        List<List<Float>> result = client.embedBatch(List.of("a", "b"), "corr-4");

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(1)).containsExactly(0.3f, 0.4f);
    }

    @Test
    void embedBatchThrowsWrappedExceptionWhenServiceReturnsErrorStatus() throws IOException {
        String url = startServer("/embed", 503, "{\"error\":\"unavailable\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        assertThatThrownBy(() -> client.embedBatch(List.of("a"), "corr-5"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to get batch embeddings");
    }

    @Test
    void embedBatchThrowsWhenResponseHasNoEmbeddingsField() throws IOException {
        String url = startServer("/embed", 200, "{\"model\":\"m\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        assertThatThrownBy(() -> client.embedBatch(List.of("a"), "corr-6"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void isHealthyReturnsTrueAndGetHealthPopulatesDetailsWhenStatusHealthy() throws IOException {
        String url = startServer("/health", 200,
                "{\"status\":\"healthy\",\"model_name\":\"m\",\"embedding_dim\":384}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        assertThat(client.isHealthy()).isTrue();
        EmbeddingClient.EmbeddingServiceHealth health = client.getHealth();
        assertThat(health.healthy()).isTrue();
        assertThat(health.modelName()).isEqualTo("m");
        assertThat(health.dimension()).isEqualTo(384);
        assertThat(health.error()).isNull();
    }

    @Test
    void getHealthReturnsUnhealthyWhenStatusIsNotHealthy() throws IOException {
        String url = startServer("/health", 200, "{\"status\":\"degraded\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        EmbeddingClient.EmbeddingServiceHealth health = client.getHealth();

        assertThat(health.healthy()).isFalse();
        assertThat(health.status()).isEqualTo("degraded");
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void getHealthReturnsHttpErrorDetailOnServerError() throws IOException {
        String url = startServer("/health", 500, "{\"error\":\"boom\"}");
        EmbeddingClient client = new EmbeddingClient(url, 5000);

        EmbeddingClient.EmbeddingServiceHealth health = client.getHealth();

        assertThat(health.healthy()).isFalse();
        assertThat(health.status()).isEqualTo("error");
        assertThat(health.error()).contains("500");
    }

    @Test
    void getHealthReturnsGenericErrorDetailWhenServiceUnreachable() {
        EmbeddingClient client = new EmbeddingClient("http://127.0.0.1:1", 500);

        EmbeddingClient.EmbeddingServiceHealth health = client.getHealth();

        assertThat(health.healthy()).isFalse();
        assertThat(health.status()).isEqualTo("error");
        assertThat(health.error()).isNotNull();
    }
}
