package org.jarvis.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jarvis.common.JarvisHttpHeaders;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(JarvisHttpHeaders.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(JarvisHttpHeaders.CORRELATION_ID, correlationId);
        HttpServletRequest wrapped = wrapRequest(request, correlationId);

        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    private HttpServletRequest wrapRequest(HttpServletRequest request, String correlationId) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if (JarvisHttpHeaders.CORRELATION_ID.equalsIgnoreCase(name)) {
                    return correlationId;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (JarvisHttpHeaders.CORRELATION_ID.equalsIgnoreCase(name)) {
                    return Collections.enumeration(Collections.singletonList(correlationId));
                }
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> headers = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                headers.add(JarvisHttpHeaders.CORRELATION_ID);
                return Collections.enumeration(headers);
            }
        };
    }
}
