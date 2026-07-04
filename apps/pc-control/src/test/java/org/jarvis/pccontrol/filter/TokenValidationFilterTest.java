package org.jarvis.pccontrol.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenValidationFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private final TokenValidationFilter filter = new TokenValidationFilter();

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void cleanupMdc() {
        MDC.clear();
    }

    @Test
    void allowsActuatorPathWithoutAuthentication() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void rejectsRequestWithoutUserIdHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/volume");
        when(request.getHeader("X-User-Id")).thenReturn(null);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
        assertTrue(body.toString().contains("Missing user authentication"));
    }

    @Test
    void rejectsRequestWithBlankUserIdHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/volume");
        when(request.getHeader("X-User-Id")).thenReturn("");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void rejectsNonAdminUserFromAdminOnlyShutdownPath() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/shutdown");
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(request.getHeader("X-User-Roles")).thenReturn("USER");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(request, response);
        assertTrue(body.toString().contains("Admin privileges required"));
    }

    @Test
    void allowsAdminUserOnAdminOnlyRestartPath() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/restart");
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertNull(MDC.get("userId"), "MDC should be cleared after the filter chain completes");
    }

    @Test
    void allowsSuperAdminUserOnAdminOnlyPath() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/shutdown");
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(request.getHeader("X-User-Roles")).thenReturn("SUPER_ADMIN");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void allowsRegularUserOnNonAdminPathWithoutRolesHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/volume");
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(request.getHeader("X-User-Roles")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(null, MDC.get("userRoles"));
    }

    @Test
    void clearsMdcEvenWhenDownstreamFilterThrows() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/pc/volume");
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        doThrow(new RuntimeException("downstream failure"))
                .when(filterChain).doFilter(request, response);

        try {
            filter.doFilterInternal(request, response, filterChain);
        } catch (RuntimeException expected) {
            // expected - MDC cleanup happens in the finally block regardless.
        }

        assertNull(MDC.get("userId"));
    }
}
