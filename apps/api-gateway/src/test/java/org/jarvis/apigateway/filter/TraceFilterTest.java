package org.jarvis.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceFilterTest {

    private final TraceFilter filter = new TraceFilter();

    @Test
    void doFilterInternalGeneratesTraceIdWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdSeenInsideChain = new AtomicReference<>();

        FilterChain chain = (req, res) -> traceIdSeenInsideChain.set(MDC.get("traceId"));

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertEquals(response.getHeader("X-Trace-Id"), traceIdSeenInsideChain.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void doFilterInternalPropagatesIncomingTraceId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("trace-123", response.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"));
    }
}
