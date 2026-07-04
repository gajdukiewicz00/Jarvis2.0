package org.jarvis.memory.service;

import org.jarvis.memory.MemoryServiceApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class MemoryServiceStartupFailureTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void startupFailsFastWhenEmbeddingDependencyIsUnavailable() throws Exception {
        assertThatThrownBy(() -> new SpringApplicationBuilder(MemoryServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "SPRING_DATASOURCE_URL=" + POSTGRES.getJdbcUrl(),
                        "SPRING_DATASOURCE_USERNAME=" + POSTGRES.getUsername(),
                        "SPRING_DATASOURCE_PASSWORD=" + POSTGRES.getPassword(),
                        "service.jwt.secret=test-service-secret-key-1234567890123456",
                        "EMBEDDING_SERVICE_URL=http://127.0.0.1:65534",
                        "memory.startup.fail-fast=true")
                .run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory-service dependencies are not ready");
    }
}
