package org.jarvis.llm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
    @NotBlank
    private String sessionId;

    @NotEmpty
    private List<@Valid ChatMessageDto> messages;

    @Min(1)
    @Max(4096)
    private Integer maxTokens;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;
}
