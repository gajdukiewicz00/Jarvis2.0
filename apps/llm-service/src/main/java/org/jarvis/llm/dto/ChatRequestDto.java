package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chat request DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {
    private String sessionId;
    private List<ChatMessageDto> messages;
    private Integer maxTokens;
    private Double temperature;
}
