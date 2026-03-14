package org.jarvis.llm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

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
        ASSISTANT;

        @JsonCreator
        public static Role fromValue(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("role must not be blank");
            }
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
    
    @NotNull
    private Role role;

    @NotBlank
    private String content;
    
    public ChatMessageDto(String role, String content) {
        this.role = Role.fromValue(role);
        this.content = content;
    }
}
