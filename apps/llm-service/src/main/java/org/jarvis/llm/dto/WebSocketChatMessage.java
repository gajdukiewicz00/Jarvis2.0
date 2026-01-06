package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket message for client communication
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketChatMessage {
    private String sessionId;
    private String message;
}
