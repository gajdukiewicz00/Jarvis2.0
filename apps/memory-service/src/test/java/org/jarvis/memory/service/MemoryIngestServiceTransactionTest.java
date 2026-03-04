package org.jarvis.memory.service;

import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.entity.ConversationMessage;
import org.jarvis.memory.repository.ConversationMessageRepository;
import org.jarvis.memory.repository.MemoryChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MemoryIngestServiceTransactionTest.TestConfig.class)
class MemoryIngestServiceTransactionTest {

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ConversationMessageRepository messageRepository() {
            return mock(ConversationMessageRepository.class);
        }

        @Bean
        MemoryChunkRepository chunkRepository() {
            return mock(MemoryChunkRepository.class);
        }

        @Bean
        EmbeddingClient embeddingClient() {
            return mock(EmbeddingClient.class);
        }

        @Bean
        ChunkingService chunkingService() {
            return mock(ChunkingService.class);
        }

        @Bean
        MemoryIngestService memoryIngestService(
                ConversationMessageRepository messageRepository,
                MemoryChunkRepository chunkRepository,
                EmbeddingClient embeddingClient,
                ChunkingService chunkingService) {
            return new MemoryIngestService(messageRepository, chunkRepository, embeddingClient, chunkingService);
        }
    }

    @jakarta.annotation.Resource
    private MemoryIngestService memoryIngestService;

    @jakarta.annotation.Resource
    private ConversationMessageRepository messageRepository;

    @jakarta.annotation.Resource
    private EmbeddingClient embeddingClient;

    @jakarta.annotation.Resource
    private ChunkingService chunkingService;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void initSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS conversation_message");
        jdbcTemplate.execute("""
                CREATE TABLE conversation_message (
                  id UUID PRIMARY KEY,
                  user_id VARCHAR(255) NOT NULL,
                  session_id VARCHAR(255) NOT NULL,
                  role VARCHAR(20) NOT NULL,
                  content CLOB NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @Test
    void shouldRollbackSavedMessagesWhenEmbeddingFails() {
        AtomicBoolean transactionSeen = new AtomicBoolean(false);

        when(chunkingService.chunkConversation(anyList())).thenReturn(List.of("user: hello"));
        when(embeddingClient.embedBatch(anyList(), anyString()))
                .thenThrow(new RuntimeException("embedding failure"));
        when(messageRepository.save(any(ConversationMessage.class))).thenAnswer(invocation -> {
            transactionSeen.set(TransactionSynchronizationManager.isActualTransactionActive());
            ConversationMessage message = invocation.getArgument(0);
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO conversation_message (id, user_id, session_id, role, content) VALUES (?, ?, ?, ?, ?)",
                    id,
                    message.getUserId(),
                    message.getSessionId(),
                    message.getRole().name(),
                    message.getContent()
            );
            message.setId(id);
            return message;
        });

        IngestRequest request = IngestRequest.builder()
                .userId("user-1")
                .sessionId("session-1")
                .createChunks(true)
                .messages(List.of(
                        IngestRequest.MessageDto.builder()
                                .role("user")
                                .content("hello")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> memoryIngestService.ingest(request, "corr-rollback"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("embedding failure");

        Integer persisted = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversation_message", Integer.class);
        assertThat(persisted).isZero();
        assertThat(transactionSeen).isTrue();
    }
}
