package org.jarvis.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter to generate and propagate trace IDs for distributed tracing
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);

        // Generate new trace ID if not present
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        // Add to MDC for logging
        MDC.put(TRACE_ID_MDC_KEY, traceId);

        // Add to response headers for client visibility
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            log.debug("Processing request with traceId: {}", traceId);
            filterChain.doFilter(request, response);
        } finally {
            // Clean up MDC
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
