package org.jarvis.security.config;

import org.hibernate.exception.JDBCConnectionException;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.UserAlreadyExistsException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the security-service exception -> HTTP mapping. Covers every
 * handler branch so error responses stay structurally correct for callers.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private WebRequest requestFor(String path) {
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=" + path);
        return request;
    }

    /**
     * Builds a real (non-mocked) {@link MethodArgumentNotValidException}. Its
     * superclass {@code BindException} exposes {@code getBindingResult()} as
     * {@code final}, so it cannot be stubbed with Mockito - constructing the
     * real object with a real {@link MapBindingResult} is the reliable option.
     */
    private MethodArgumentNotValidException validationExceptionWithErrors(FieldError... errors)
            throws NoSuchMethodException {
        Method anyMethod = Object.class.getMethod("equals", Object.class);
        MethodParameter methodParameter = new MethodParameter(anyMethod, 0);
        MapBindingResult bindingResult = new MapBindingResult(new HashMap<>(), "registerRequest");
        for (FieldError error : errors) {
            bindingResult.addError(error);
        }
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    @Test
    void handlesAuthenticationExceptionAsUnauthorized() {
        ResponseEntity<Map<String, Object>> response = handler.handleAuthenticationException(
                new AuthenticationException("INVALID_CREDENTIALS", "Invalid username or password"),
                requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "INVALID_CREDENTIALS");
        assertThat(response.getBody()).containsEntry("message", "Invalid username or password");
        assertThat(response.getBody()).containsEntry("service", "security-service");
        assertThat(response.getBody()).containsEntry("path", "/auth/login");
    }

    @Test
    void handlesUserAlreadyExistsAsConflict() {
        ResponseEntity<Map<String, Object>> response = handler.handleUserAlreadyExistsException(
                new UserAlreadyExistsException("Username 'alice' already exists"),
                requestFor("/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "USER_ALREADY_EXISTS");
    }

    @Test
    void handlesValidationExceptionJoiningMultipleFieldErrors() throws NoSuchMethodException {
        MethodArgumentNotValidException ex = validationExceptionWithErrors(
                new FieldError("registerRequest", "username", "Username is required"),
                new FieldError("registerRequest", "password", "Password is required"));

        ResponseEntity<Map<String, Object>> response = handler.handleValidationException(
                ex, requestFor("/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(response.getBody().get("message"))
                .isEqualTo("username: Username is required; password: Password is required");
    }

    @Test
    void handlesValidationExceptionWithNoFieldErrorsUsingFallbackMessage() throws NoSuchMethodException {
        MethodArgumentNotValidException ex = validationExceptionWithErrors();

        ResponseEntity<Map<String, Object>> response = handler.handleValidationException(
                ex, requestFor("/auth/register"));

        assertThat(response.getBody()).containsEntry("message", "Validation failed");
    }

    @Test
    void handlesIllegalArgumentExceptionAsBadRequest() {
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("New password must be different from the current password"),
                requestFor("/auth/password/change"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "INVALID_REQUEST");
    }

    @Test
    void handlesCannotCreateTransactionExceptionAsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = handler.handleDatabaseConnectionException(
                new CannotCreateTransactionException("could not open connection"),
                requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void handlesHibernateJdbcConnectionExceptionAsServiceUnavailable() {
        JDBCConnectionException ex = new JDBCConnectionException("db down", new SQLException("conn refused"));

        ResponseEntity<Map<String, Object>> response = handler.handleDatabaseConnectionException(
                ex, requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void handlesDataIntegrityViolationForUniqueConstraintAsConflict() {
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("unique constraint [users_username_key] violated"),
                requestFor("/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "USER_ALREADY_EXISTS");
        assertThat(response.getBody()).containsEntry("message", "Username already exists");
    }

    @Test
    void handlesDataIntegrityViolationForOtherCausesAsInternalError() {
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("foreign key constraint violated"),
                requestFor("/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "DATA_ERROR");
    }

    @Test
    void handlesSqlExceptionWithConnectionStateAsServiceUnavailable() {
        SQLException ex = new SQLException("connection refused", "08001");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(
                ex, requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void handlesSqlExceptionWithNonConnectionStateAsDatabaseError() {
        SQLException ex = new SQLException("constraint violated", "23505");

        ResponseEntity<Map<String, Object>> response = handler.handleSQLException(
                ex, requestFor("/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "DATABASE_ERROR");
    }

    @Test
    void handlesDataAccessExceptionWithSqlRootCauseByDelegatingToSqlHandler() {
        SQLException sqlCause = new SQLException("connection refused", "08001");
        DataAccessResourceFailureException ex = new DataAccessResourceFailureException("db down", sqlCause);

        ResponseEntity<Map<String, Object>> response = handler.handleDataAccessException(
                ex, requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void handlesDataAccessExceptionWithoutSqlRootCauseAsServiceUnavailable() {
        DataAccessResourceFailureException ex = new DataAccessResourceFailureException("weird failure");

        ResponseEntity<Map<String, Object>> response = handler.handleDataAccessException(
                ex, requestFor("/auth/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AUTH_SERVICE_UNAVAILABLE");
        assertThat(response.getBody()).containsEntry("message", "Authentication temporarily unavailable");
    }

    @Test
    void handlesGenericExceptionAsInternalError() {
        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(
                new RuntimeException("boom"), requestFor("/auth/me"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "INTERNAL_ERROR");
        assertThat(response.getBody()).containsEntry("message", "An unexpected error occurred");
    }

    @Test
    void authenticationExceptionExposesErrorCode() {
        AuthenticationException ex = new AuthenticationException("TOKEN_EXPIRED", "Token has expired");

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_EXPIRED");
        assertThat(ex.getMessage()).isEqualTo("Token has expired");
    }
}
