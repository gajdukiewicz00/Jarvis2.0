package org.jarvis.common.filter;

import org.jarvis.common.JarvisHttpHeaders;
import org.jarvis.common.testsupport.FakeHttpServletRequest;
import org.jarvis.common.testsupport.FakeHttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracePropagationFilterTest {

    private final TracePropagationFilter filter = new TracePropagationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsTraceIdIntoMdcDuringChainAndClearsItAfterwards() throws Exception {
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(JarvisHttpHeaders.TRACE_ID, "trace-123");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilterInternal(request, response, (req, res) ->
                mdcDuringChain.set(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY)));

        assertEquals("trace-123", mdcDuringChain.get());
        assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY));
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(JarvisHttpHeaders.TRACE_ID, "trace-456");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        try {
            filter.doFilterInternal(request, response, (req, res) -> {
                throw new java.io.IOException("boom");
            });
        } catch (Exception ignored) {
            // expected: rethrown after MDC cleanup
        }

        assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY));
    }

    @Test
    void missingHeaderLeavesMdcUntouched() throws Exception {
        FakeHttpServletRequest request = new FakeHttpServletRequest();
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilterInternal(request, response, (req, res) -> {
            chainCalled.set(true);
            assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY));
        });

        assertTrue(chainCalled.get());
        assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY));
    }

    @Test
    void emptyHeaderValueLeavesMdcUntouched() throws Exception {
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(JarvisHttpHeaders.TRACE_ID, "");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) ->
                assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY)));

        assertNull(MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY));
    }
}
