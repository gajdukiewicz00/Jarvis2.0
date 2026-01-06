package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * WebSocket response for client
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketChatResponse {
    private String sessionId;
    private String reply;
    private Instant timestamp;
    private Integer processingTimeMs;
}
