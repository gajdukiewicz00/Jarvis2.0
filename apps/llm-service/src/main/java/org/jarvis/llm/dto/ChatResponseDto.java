package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.llm.model.Emotion;

import java.util.Map;

/**
 * Chat response DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDto {
    private String reply;
    private Map<String, Integer> tokens;
    private String model;
    private Integer processingTimeMs;
    private Emotion emotion; // For TTS voice tone
}
