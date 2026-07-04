package org.jarvis.lifetracker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private WebRequest webRequest;

    @BeforeEach
    void setUp() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/v1/life/finance/transactions");
    }

    @Test
    void handleDatabaseConnectionExceptionReturns503() {
        ResponseEntity<Map<String, Object>> response = handler.handleDatabaseConnectionException(
                new CannotCreateTransactionException("no connection"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "DATABASE_UNAVAILABLE");
        assertThat(response.getBody()).containsEntry("service", "life-tracker");
        assertThat(response.getBody()).containsEntry("path", "/api/v1/life/finance/transactions");
        assertThat(response.getBody()).containsKey("timestamp");
    }

    @Test
    void handleSqlExceptionWithConstraintViolationStateReturns409() {
        SQLException ex = new SQLException("duplicate key Detail: Key already exists.", "23505");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CONSTRAINT_VIOLATION");
        assertThat(response.getBody().get("message").toString()).contains("Key already exists.");
    }

    @Test
    void handleSqlExceptionWithConnectionStateReturns503() {
        SQLException ex = new SQLException("connection refused", "08001");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "DATABASE_UNAVAILABLE");
    }

    @Test
    void handleSqlExceptionWithUnknownStateReturns500() {
        SQLException ex = new SQLException("weird error", "99999");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "DATABASE_ERROR");
    }

    @Test
    void handleSqlExceptionWithNullStateReturns500() {
        SQLException ex = new SQLException("weird error", (String) null);

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "DATABASE_ERROR");
    }

    @Test
    void handleSqlExceptionWithoutDetailReturnsGenericMessage() {
        SQLException ex = new SQLException("duplicate key", "23505");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(ex, webRequest);

        assertThat(response.getBody().get("message").toString()).contains("Please check your input data.");
    }

    @Test
    void handleDataAccessExceptionWithSqlRootCauseDelegatesToSqlHandler() {
        SQLException sqlEx = new SQLException("dup", "23505");
        DataAccessException ex = new DataIntegrityViolationException("wrapped", sqlEx);

        ResponseEntity<Map<String, Object>> response = handler.handleDataAccessException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CONSTRAINT_VIOLATION");
    }

    @Test
    void handleDataAccessExceptionWithoutSqlRootCauseReturns500() {
        DataAccessException ex = new DataIntegrityViolationException("no root cause");

        ResponseEntity<Map<String, Object>> response = handler.handleDataAccessException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "DATA_ACCESS_ERROR");
    }

    @Test
    void handleIllegalArgumentExceptionReturns400() {
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("title is required"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(response.getBody()).containsEntry("message", "title is required");
    }

    @Test
    void handleResponseStatusExceptionWithReasonUppercasesErrorCode() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatusException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "MISSING_USER_ID");
        assertThat(response.getBody()).containsEntry("message", "missing user id");
    }

    @Test
    void handleResponseStatusExceptionWithoutReasonUsesStatusName() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatusException(ex, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "Request failed");
    }

    @Test
    void handleGenericExceptionReturns500() {
        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(
                new RuntimeException("unexpected"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "INTERNAL_ERROR");
    }
}
