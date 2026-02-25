package org.jarvis.llm.controller;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatRequestDto;
import org.jarvis.llm.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LlmRestControllerGuardTest {

    @Test
    void shouldReturnBadRequestWhenMessagesAreEmpty() {
        LlmService llmService = mock(LlmService.class);
        LlmRestController controller = new LlmRestController(llmService);
        ChatRequestDto request = new ChatRequestDto("session-1", List.of(), 256, 0.5);

        ResponseEntity<?> response = controller.chat(request, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldReturnBadRequestWhenLastMessageIsBlank() {
        LlmService llmService = mock(LlmService.class);
        LlmRestController controller = new LlmRestController(llmService);
        ChatMessageDto message = new ChatMessageDto(ChatMessageDto.Role.USER, "   ");
        ChatRequestDto request = new ChatRequestDto("session-1", List.of(message), 256, 0.5);

        ResponseEntity<?> response = controller.chat(request, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(llmService);
    }
}
