package org.jarvis.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.JarvisHttpHeaders;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(JarvisHttpHeaders.TRACE_ID);

        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(JarvisHttpHeaders.TRACE_ID_MDC_KEY, traceId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(JarvisHttpHeaders.TRACE_ID_MDC_KEY);
        }
    }
}

