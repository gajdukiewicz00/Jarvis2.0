package org.jarvis.orchestrator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.jarvis.orchestrator.client.ApiGatewayVisionClient;
import org.junit.jupiter.api.Test;

class InternalVisionControllerTest {

    private final ApiGatewayVisionClient client = mock(ApiGatewayVisionClient.class);
    private final InternalVisionController controller = new InternalVisionController(client);

    @Test
    void askScreenMapsVisionAnswerToResponseAnswerWithAnalyzedStatus() {
        when(client.askScreen(eq("2"), any()))
                .thenReturn(Map.of("question", "Что на экране?", "answer",
                        "На экране открыт редактор кода VS Code с Java-файлом.", "success", true));

        Map<String, Object> response = controller.askScreen(
                new InternalVisionController.AskScreenRequest("Что на экране?", "2", "corr-1"));

        assertEquals("analyzed", response.get("status"));
        assertEquals("На экране открыт редактор кода VS Code с Java-файлом.", response.get("answer"));
        assertFalse(response.containsKey("failureReason"), "success response must not carry a failureReason");
    }

    @Test
    void askScreenDefaultsQuestionWhenNullAndStillAnalyzes() {
        when(client.askScreen(eq("2"), any()))
                .thenReturn(Map.of("answer", "Рабочий стол.", "success", true));

        Map<String, Object> response = controller.askScreen(
                new InternalVisionController.AskScreenRequest(null, "2", "corr-2"));

        assertEquals("analyzed", response.get("status"));
        assertEquals("Рабочий стол.", response.get("answer"));
    }

    @Test
    void askScreenReturnsVisionFailedWithHttpCodeOnDownstream5xx() {
        Request request = Request.create(Request.HttpMethod.POST,
                "http://api-gateway:8080/api/v1/vision-security/cv/ask-screen",
                Collections.emptyMap(), null, StandardCharsets.UTF_8);
        Response downstream = Response.builder()
                .status(500)
                .reason("Internal Server Error")
                .request(request)
                .headers(Collections.emptyMap())
                .build();
        FeignException serverError = FeignException.errorStatus("askScreen", downstream);
        when(client.askScreen(eq("2"), any())).thenThrow(serverError);

        Map<String, Object> response = controller.askScreen(
                new InternalVisionController.AskScreenRequest("Что на экране?", "2", "corr-3"));

        assertEquals("vision_failed", response.get("status"));
        assertEquals("VISION_HTTP_500", response.get("failureReason"));
        assertEquals("", response.get("answer"));
    }

    @Test
    void askScreenReturnsVisionFailedUnavailableOnGenericException() {
        when(client.askScreen(eq("2"), any())).thenThrow(new RuntimeException("connection refused"));

        Map<String, Object> response = controller.askScreen(
                new InternalVisionController.AskScreenRequest("Что на экране?", "2", "corr-4"));

        assertEquals("vision_failed", response.get("status"));
        assertEquals("VISION_UNAVAILABLE", response.get("failureReason"));
    }

    @Test
    void askScreenReturnsVisionFailedEmptyWhenAnswerBlank() {
        when(client.askScreen(eq("2"), any()))
                .thenReturn(Map.of("answer", "   ", "success", true));

        Map<String, Object> response = controller.askScreen(
                new InternalVisionController.AskScreenRequest("Что на экране?", "2", "corr-5"));

        assertEquals("vision_failed", response.get("status"));
        assertEquals("VISION_EMPTY", response.get("failureReason"));
        assertTrue(String.valueOf(response.get("answer")).isEmpty());
    }
}
