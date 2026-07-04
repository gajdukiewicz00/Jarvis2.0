package org.jarvis.memory.obsidian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryEmbeddingClient} builds its own internal {@code RestTemplate}
 * (no DI seam), so these tests point it at a real, ephemeral-port
 * {@code com.sun.net.httpserver.HttpServer} instead of mocking — no extra
 * test dependency needed since it ships with the JDK.
 */
class MemoryEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
    }

    private MemoryEmbeddingClient client(String url, boolean enabled) {
        return new MemoryEmbeddingClient(new ObjectMapper(), url, enabled, 2000);
    }

    @Test
    void embedReturnsNullWhenDisabled() {
        assertThat(client("http://127.0.0.1:1/embed", false).embed("hello")).isNull();
    }

    @Test
    void embedReturnsNullForBlankOrNullText() {
        MemoryEmbeddingClient c = client("http://127.0.0.1:1/embed", true);
        assertThat(c.embed("   ")).isNull();
        assertThat(c.embed(null)).isNull();
    }

    @Test
    void embedParsesBatchEmbeddingsShape() throws IOException {
        String url = startServer(200, "{\"embeddings\":[[0.1,0.2,0.3]]}");

        assertThat(client(url, true).embed("some text")).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void embedFallsBackToSingularEmbeddingKey() throws IOException {
        String url = startServer(200, "{\"embedding\":[0.4,0.5]}");

        assertThat(client(url, true).embed("text")).containsExactly(0.4f, 0.5f);
    }

    @Test
    void embedFallsBackToVectorKey() throws IOException {
        String url = startServer(200, "{\"vector\":[0.6,0.7]}");

        assertThat(client(url, true).embed("text")).containsExactly(0.6f, 0.7f);
    }

    @Test
    void embedFallsBackToDataKey() throws IOException {
        String url = startServer(200, "{\"data\":[0.8,0.9]}");

        assertThat(client(url, true).embed("text")).containsExactly(0.8f, 0.9f);
    }

    @Test
    void embedReturnsNullWhenEmbeddingsOuterListIsEmpty() throws IOException {
        String url = startServer(200, "{\"embeddings\":[]}");

        assertThat(client(url, true).embed("text")).isNull();
    }

    @Test
    void embedReturnsNullWhenAnElementIsNotANumber() throws IOException {
        String url = startServer(200, "{\"embedding\":[\"not-a-number\", 1]}");

        assertThat(client(url, true).embed("text")).isNull();
    }

    @Test
    void embedReturnsNullWhenResponseBodyIsJsonNull() throws IOException {
        String url = startServer(200, "null");

        assertThat(client(url, true).embed("text")).isNull();
    }

    @Test
    void embedReturnsNullOnServerError() throws IOException {
        String url = startServer(500, "{\"error\":\"boom\"}");

        assertThat(client(url, true).embed("text")).isNull();
    }

    @Test
    void embedReturnsNullWhenServiceUnreachable() {
        assertThat(client("http://127.0.0.1:1/embed", true).embed("text")).isNull();
    }
}
