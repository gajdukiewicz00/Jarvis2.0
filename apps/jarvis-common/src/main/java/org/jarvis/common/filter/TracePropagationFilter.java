package org.jarvis.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to extract trace ID from headers and add to MDC for logging.
 * Shared across all Jarvis microservices.
 *
 * <p>Registered as a bean by {@link org.jarvis.common.JarvisCommonAutoConfiguration}.</p>
 */
@Slf4j
public class TracePropagationFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);

        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}

