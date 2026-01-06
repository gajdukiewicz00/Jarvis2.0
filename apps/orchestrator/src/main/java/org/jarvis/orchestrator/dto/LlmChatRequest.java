package org.jarvis.orchestrator.dto;

import java.util.List;

public record LlmChatRequest(
        String sessionId,
        List<Message> messages) {
    public record Message(String role, String content) {
    }
}
