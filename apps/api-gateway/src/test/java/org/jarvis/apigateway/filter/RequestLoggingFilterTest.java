package org.jarvis.apigateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void doFilterCopiesWrappedResponseBodyBackToClientResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":\"ok\"}");
        });

        assertEquals(200, response.getStatus());
        assertEquals("{\"status\":\"ok\"}", response.getContentAsString());
    }
}
