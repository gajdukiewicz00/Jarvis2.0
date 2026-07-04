package org.jarvis.memory.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jarvis.memory.MemoryServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class MemoryServiceSharedSchemaStartupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static HttpServer embeddingServer;

    @AfterAll
    static void tearDown() {
        if (embeddingServer != null) {
            embeddingServer.stop(0);
        }
    }

    @Test
    void startupCreatesMemoryTablesEvenWhenSharedSchemaIsAlreadyNonEmpty() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        jdbcTemplate.execute("create extension if not exists vector");
        jdbcTemplate.execute("create table if not exists existing_service_table(id uuid primary key default gen_random_uuid())");

        startEmbeddingServer();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MemoryServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "SPRING_DATASOURCE_URL=" + POSTGRES.getJdbcUrl(),
                        "SPRING_DATASOURCE_USERNAME=" + POSTGRES.getUsername(),
                        "SPRING_DATASOURCE_PASSWORD=" + POSTGRES.getPassword(),
                        "EMBEDDING_SERVICE_URL=http://127.0.0.1:" + embeddingServer.getAddress().getPort(),
                        "memory.startup.fail-fast=true")
                .run()) {
            assertThat(context.isActive()).isTrue();
        }

        assertThat(tableExists(jdbcTemplate, "flyway_schema_history_memory")).isTrue();
        assertThat(tableExists(jdbcTemplate, "conversation_message")).isTrue();
        assertThat(tableExists(jdbcTemplate, "memory_chunk")).isTrue();
        assertThat(tableExists(jdbcTemplate, "session_summary")).isTrue();
    }

    private static synchronized void startEmbeddingServer() throws IOException {
        if (embeddingServer != null) {
            return;
        }

        embeddingServer = HttpServer.create(new InetSocketAddress(0), 0);
        embeddingServer.createContext("/health", exchange -> writeJson(exchange, 200,
                "{\"status\":\"healthy\",\"model_name\":\"test-embedder\",\"embedding_dim\":384}"));
        embeddingServer.start();
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class,
                tableName);
        return count != null && count > 0;
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
