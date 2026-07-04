package org.jarvis.llm.controller;

import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.WebSocketChatMessage;
import org.jarvis.llm.dto.WebSocketChatResponse;
import org.jarvis.llm.model.Emotion;
import org.jarvis.llm.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmWebSocketControllerTest {

    private LlmService llmService;
    private SimpMessagingTemplate messagingTemplate;
    private LlmWebSocketController controller;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        controller = new LlmWebSocketController(llmService, messagingTemplate);
    }

    @Test
    void handleChatMessageSendsResponseToSessionTopic() {
        when(llmService.processMessage(anyString(), anyString(), anyString()))
                .thenReturn(new ChatResponseDto("Привет!", null, "model", 25, Emotion.NEUTRAL));

        WebSocketChatMessage message = new WebSocketChatMessage("session-1", "Привет");
        controller.handleChatMessage(message);

        ArgumentCaptor<WebSocketChatResponse> captor = ArgumentCaptor.forClass(WebSocketChatResponse.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/llm-response/session-1"),
                captor.capture());

        WebSocketChatResponse response = captor.getValue();
        assertThat(response.getSessionId()).isEqualTo("session-1");
        assertThat(response.getReply()).isEqualTo("Привет!");
        assertThat(response.getProcessingTimeMs()).isEqualTo(25);
    }

    @Test
    void handleChatMessageSendsErrorResponseWhenServiceThrows() {
        when(llmService.processMessage(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        WebSocketChatMessage message = new WebSocketChatMessage("session-2", "Hi");
        controller.handleChatMessage(message);

        ArgumentCaptor<WebSocketChatResponse> captor = ArgumentCaptor.forClass(WebSocketChatResponse.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/llm-response/session-2"),
                captor.capture());

        WebSocketChatResponse response = captor.getValue();
        assertThat(response.getSessionId()).isEqualTo("session-2");
        assertThat(response.getReply()).contains("ошибка");
        assertThat(response.getProcessingTimeMs()).isEqualTo(0);
    }
}
