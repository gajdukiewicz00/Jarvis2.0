package org.jarvis.vision.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class VisionRequestContextFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = headerOrGenerated(request, REQUEST_ID_HEADER);
        String correlationId = headerOrDefault(request, CORRELATION_ID_HEADER, requestId);

        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        MDC.put("traceId", correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("correlationId");
            MDC.remove("requestId");
        }
    }

    private static String headerOrGenerated(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static String headerOrDefault(HttpServletRequest request, String headerName, String fallback) {
        String value = request.getHeader(headerName);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
