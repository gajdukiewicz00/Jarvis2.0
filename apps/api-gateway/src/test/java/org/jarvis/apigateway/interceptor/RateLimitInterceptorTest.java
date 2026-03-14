package org.jarvis.apigateway.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor();
    }

    @Test
    void preHandleReturnsTrueWhenRateLimitingIsDisabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "rateLimitEnabled", false);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/test"),
                new MockHttpServletResponse(),
                new Object());

        assertTrue(allowed);
    }

    @Test
    void preHandleInitializesRateLimiterAndAllowsRequestWhenPermitIsAvailable() throws Exception {
        ReflectionTestUtils.setField(interceptor, "rateLimitEnabled", true);
        ReflectionTestUtils.setField(interceptor, "maxRequestsPerMinute", 120);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/test"),
                response,
                new Object());

        assertTrue(allowed);
        assertNotNull(ReflectionTestUtils.getField(interceptor, "rateLimiter"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void preHandleReturnsTooManyRequestsWhenLimiterRejectsCall() throws Exception {
        ReflectionTestUtils.setField(interceptor, "rateLimitEnabled", true);
        RateLimiter mockedLimiter = mock(RateLimiter.class);
        when(mockedLimiter.tryAcquire()).thenReturn(false);
        ReflectionTestUtils.setField(interceptor, "rateLimiter", mockedLimiter);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/test"),
                response,
                new Object());

        assertEquals(false, allowed);
        assertEquals(429, response.getStatus());
        assertEquals("{\"error\":\"Rate limit exceeded. Please try again later.\"}", response.getContentAsString());
    }
}
