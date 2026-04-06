package org.jarvis.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryServicePgvectorIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicReference<String> LAST_SINGLE_INPUT_TYPE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BATCH_INPUT_TYPE = new AtomicReference<>();
    private static HttpServer embeddingServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        startEmbeddingServer();
        registry.add("SPRING_DATASOURCE_URL", () -> POSTGRES.getJdbcUrl());
        registry.add("SPRING_DATASOURCE_USERNAME", () -> POSTGRES.getUsername());
        registry.add("SPRING_DATASOURCE_PASSWORD", () -> POSTGRES.getPassword());
        registry.add("EMBEDDING_SERVICE_URL", () -> "http://127.0.0.1:" + embeddingServer.getAddress().getPort());
        registry.add("service.jwt.secret", () -> "test-service-secret-key-1234567890123456");
        registry.add("memory.startup.fail-fast", () -> "true");
    }

    @BeforeAll
    static void setupExtensions() throws Exception {
        startEmbeddingServer();
    }

    @AfterAll
    static void tearDown() {
        if (embeddingServer != null) {
            embeddingServer.stop(0);
        }
    }

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private MemoryDependencyStatusService dependencyStatusService;

    @Autowired
    private MemoryChunkRepository chunkRepository;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void storeAndRetrieveMemoryAgainstRealPgvector() {
        String userId = "integration-user";
        String sessionId = "integration-session";

        memoryService.ingest(IngestRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .createChunks(true)
                .messages(List.of(
                        IngestRequest.MessageDto.builder()
                                .role("user")
                                .content("Remember this: my name is Denis and I build backend systems in Warsaw.")
                                .build(),
                        IngestRequest.MessageDto.builder()
                                .role("assistant")
                                .content("Stored. You are Denis, a backend engineer in Warsaw.")
                                .build()))
                .metadata(Map.of("source", "integration-test"))
                .build(), "it-ingest");
        assertThat(LAST_BATCH_INPUT_TYPE.get()).isEqualTo("passage");

        SearchResponse response = memoryService.search(SearchRequest.builder()
                .userId(userId)
                .query("what is my name")
                .topK(3)
                .maxTokens(200)
                .build(), "it-search");

        assertThat(messageRepository.countBySessionId(sessionId)).isEqualTo(2);
        assertThat(chunkRepository.countByUserId(userId)).isGreaterThan(0);
        List<Object[]> nearestChunks = chunkRepository.findSimilarWithDistance(
                userId,
                vectorForText("what is my name"),
                0.5d,
                PageRequest.of(0, 3));

        assertThat(nearestChunks).isNotEmpty();
        assertThat(((Number) nearestChunks.getFirst()[1]).doubleValue()).isLessThan(0.5d);
        assertThat(response.getChunks()).isNotEmpty();
        assertThat(response.getRetrievalMode()).isEqualTo("semantic");
        assertThat(response.getContextText().toLowerCase()).contains("denis");
        assertThat(LAST_SINGLE_INPUT_TYPE.get()).isEqualTo("query");
    }

    @Test
    void healthEndpointReportsDatabasePgvectorAndEmbeddingState() {
        @SuppressWarnings("unchecked")
        org.springframework.http.ResponseEntity<Map> response = restTemplate.getForEntity(
                "http://127.0.0.1:" + port + "/memory/health", Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("healthy");
        assertThat(body.get("database")).isEqualTo("up");
        assertThat(body.get("pgvector")).isEqualTo("available");
        assertThat(body.get("embeddingService")).isEqualTo("up");

        MemoryDependencyStatusService.DependencyStatus status = dependencyStatusService.checkDependencies();
        assertThat(status.status()).isEqualTo("healthy");
    }

    private static synchronized void startEmbeddingServer() {
        if (embeddingServer != null) {
            return;
        }

        try {
            embeddingServer = HttpServer.create(new InetSocketAddress(0), 0);
            embeddingServer.createContext("/health", exchange -> writeJson(exchange, 200,
                    "{\"status\":\"healthy\",\"model_name\":\"test-embedder\",\"embedding_dim\":384}"));
            embeddingServer.createContext("/embed/single", exchange -> {
                JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
                String text = request.path("text").asText("");
                String inputType = request.path("input_type").asText("query");
                LAST_SINGLE_INPUT_TYPE.set(inputType);
                writeJson(exchange, 200, embedSingleResponse(text));
            });
            embeddingServer.createContext("/embed", exchange -> {
                JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
                String inputType = request.path("input_type").asText("query");
                LAST_BATCH_INPUT_TYPE.set(inputType);
                writeJson(exchange, 200, embedBatchResponse(request.path("texts")));
            });
            embeddingServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start embedding stub", ex);
        }
    }

    private static String embedSingleResponse(String text) throws IOException {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "embedding", vectorForText(text),
                "model", "test-embedder",
                "dimension", 384,
                "processing_time_ms", 1
        ));
    }

    private static String embedBatchResponse(JsonNode textsNode) throws IOException {
        List<float[]> embeddings = textsNode.isArray()
                ? java.util.stream.StreamSupport.stream(textsNode.spliterator(), false)
                        .map(node -> vectorForText(node.asText("")))
                        .toList()
                : List.of();

        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "embeddings", embeddings,
                "model", "test-embedder",
                "dimension", 384,
                "count", embeddings.size(),
                "processing_time_ms", 1
        ));
    }

    private static float[] vectorForText(String text) {
        float[] vector = new float[384];
        String normalized = text.toLowerCase();
        vector[0] = normalized.contains("denis") || normalized.contains("name") ? 1.0f : 0.1f;
        vector[1] = normalized.contains("backend") ? 0.9f : 0.1f;
        vector[2] = normalized.contains("warsaw") ? 0.8f : 0.1f;
        vector[3] = normalized.contains("name") || normalized.contains("denis") ? 1.0f : 0.1f;
        return vector;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
