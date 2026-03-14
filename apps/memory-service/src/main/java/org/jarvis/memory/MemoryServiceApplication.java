package org.jarvis.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Jarvis Memory Service
 * 
 * Provides long-term memory storage and semantic search using PostgreSQL + pgvector.
 * 
 * Features:
 * - Store conversation messages
 * - Chunk and embed messages for vector search
 * - Semantic search across historical conversations
 * - Session summaries
 */
@SpringBootApplication
public class MemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}


