package org.jarvis.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseToolExceptionHandlerTest {

    private final TestHandler handler = new TestHandler();

    @Test
    void handleIdempotencyConflictReturnsConflictPayload() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleIdempotencyConflict(new IdempotencyConflictException("same key, different payload"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("idempotency_conflict", response.getBody().get("error"));
        assertEquals("same key, different payload", response.getBody().get("message"));
    }

    @Test
    void handleValidationReturnsFieldLevelDetails() throws Exception {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new TestPayload(), "payload");
        bindingResult.addError(new FieldError("payload", "title", "must not be blank"));
        Method method = TestController.class.getDeclaredMethod("accept", TestPayload.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("validation_error", response.getBody().get("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> details = (List<Map<String, String>>) response.getBody().get("details");
        assertEquals(List.of(Map.of("field", "title", "message", "must not be blank")), details);
    }

    @Test
    void handleConstraintViolationReturnsValidationErrorPayload() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleConstraintViolation(new ConstraintViolationException("priority: must not be null", null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("validation_error", response.getBody().get("error"));
        assertEquals("priority: must not be null", response.getBody().get("message"));
    }

    @Test
    void handleUnreadableReturnsMostSpecificCauseMessage() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Unreadable",
                new IllegalArgumentException("Cannot deserialize value of type TaskPriority"));

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadable(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_payload", response.getBody().get("error"));
        assertEquals("Cannot deserialize value of type TaskPriority", response.getBody().get("message"));
    }

    @Test
    void handleIllegalArgumentReturnsInvalidPayload() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("priority is invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_payload", response.getBody().get("error"));
        assertEquals("priority is invalid", response.getBody().get("message"));
    }

    private static class TestHandler extends BaseToolExceptionHandler {
    }

    private static class TestController {
        void accept(TestPayload payload) {
        }
    }

    private static class TestPayload {
        private String title;
    }
}
