package org.jarvis.voicegateway.confirmation;

import org.jarvis.commands.CommandTopology;
import org.jarvis.commands.ConfirmationDecision;
import org.jarvis.commands.ConfirmationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class VoiceConfirmationControllerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private VoiceConfirmationController controller;

    @Test
    void publishesApprovedDecisionOnConfirmationResultQueue() {
        VoiceConfirmationController.VoiceConfirmation body =
                new VoiceConfirmationController.VoiceConfirmation(
                        "cmd-1", "corr-1", ConfirmationDecision.APPROVED, "user-42", "ok");

        ResponseEntity<ConfirmationResult> response = controller.submit(body);

        assertEquals(202, response.getStatusCode().value());
        ArgumentCaptor<ConfirmationResult> captor = ArgumentCaptor.forClass(ConfirmationResult.class);
        verify(rabbitTemplate).convertAndSend(eq(""),
                eq(CommandTopology.QUEUE_CONFIRMATION_RESULT), captor.capture());
        ConfirmationResult published = captor.getValue();
        assertEquals("cmd-1", published.getCommandId());
        assertEquals(ConfirmationDecision.APPROVED, published.getDecision());
        assertEquals("user-42", published.getDecidedBy());
        assertEquals("voice", published.getChannel());
    }

    @Test
    void rejectsBlankCommandIdWithoutPublishing() {
        VoiceConfirmationController.VoiceConfirmation body =
                new VoiceConfirmationController.VoiceConfirmation(
                        "  ", "corr-1", ConfirmationDecision.APPROVED, "user-42", null);

        ResponseEntity<ConfirmationResult> response = controller.submit(body);

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void returnsServiceUnavailableWhenBrokerPublishFails() {
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));
        VoiceConfirmationController.VoiceConfirmation body =
                new VoiceConfirmationController.VoiceConfirmation(
                        "cmd-2", null, ConfirmationDecision.DENIED, "user-7", "no");

        ResponseEntity<ConfirmationResult> response = controller.submit(body);

        assertEquals(503, response.getStatusCode().value());
    }
}
