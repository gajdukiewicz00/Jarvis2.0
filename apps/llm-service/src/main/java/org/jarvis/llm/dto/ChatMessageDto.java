package org.jarvis.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single chat message DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
    
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
    
    @NotNull
    private Role role;

    @NotBlank
    private String content;
    
    public ChatMessageDto(String role, String content) {
        this.role = Role.valueOf(role.toUpperCase());
        this.content = content;
    }
}
