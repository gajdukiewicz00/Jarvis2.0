package org.jarvis.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request to ingest messages into memory
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "sessionId is required")
    private String sessionId;

    @NotEmpty(message = "messages cannot be empty")
    private List<MessageDto> messages;

    /**
     * Optional: create chunks and embeddings
     */
    @Builder.Default
    private boolean createChunks = true;

    /**
     * Optional: additional metadata to attach to messages
     */
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageDto {
        @NotBlank
        private String role;  // user, assistant, system
        
        @NotBlank
        private String content;
        
        private Map<String, Object> metadata;
    }
}



