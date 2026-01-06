package org.jarvis.llm.dto;

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
    
    private Role role;
    private String content;
    
    public ChatMessageDto(String role, String content) {
        this.role = Role.valueOf(role.toUpperCase());
        this.content = content;
    }
}
