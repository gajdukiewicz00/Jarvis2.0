package org.jarvis.common.ratelimit;

import org.jarvis.common.testsupport.FakeHttpServletRequest;
import org.jarvis.common.testsupport.FakeHttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    @Test
    void firstRequestUnderLimitIsAllowed() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 3600);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader("X-User-Id", "user-1")
                .withRequestURI("/api/a");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void secondRequestOverLimitIsRejectedWith429JsonBody() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 3600);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader("X-User-Id", "user-2")
                .withRequestURI("/api/a");

        assertTrue(interceptor.preHandle(request, new FakeHttpServletResponse(), new Object()));

        FakeHttpServletResponse secondResponse = new FakeHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, secondResponse, new Object());

        assertFalse(allowed);
        assertEquals(429, secondResponse.getStatus());
        assertEquals("application/json", secondResponse.getContentType());
        String body = secondResponse.bodyAsString();
        assertTrue(body.contains("Rate limit exceeded"));
        assertTrue(body.contains("\"retryAfter\":3600"));
    }

    @Test
    void perEndpointFalseSharesLimiterAcrossDifferentEndpoints() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 3600, false);
        String user = "user-3";

        assertTrue(interceptor.preHandle(
                new FakeHttpServletRequest().withHeader("X-User-Id", user).withRequestURI("/api/a"),
                new FakeHttpServletResponse(), new Object()));
        boolean secondEndpointAllowed = interceptor.preHandle(
                new FakeHttpServletRequest().withHeader("X-User-Id", user).withRequestURI("/api/b"),
                new FakeHttpServletResponse(), new Object());

        assertFalse(secondEndpointAllowed);
    }

    @Test
    void perEndpointTrueGivesIndependentLimiterPerEndpoint() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 3600, true);
        String user = "user-4";

        assertTrue(interceptor.preHandle(
                new FakeHttpServletRequest().withHeader("X-User-Id", user).withRequestURI("/api/a"),
                new FakeHttpServletResponse(), new Object()));
        boolean secondEndpointAllowed = interceptor.preHandle(
                new FakeHttpServletRequest().withHeader("X-User-Id", user).withRequestURI("/api/b"),
                new FakeHttpServletResponse(), new Object());

        assertTrue(secondEndpointAllowed);
    }

    @Test
    void getUserIdFallsBackToRemoteAddrWhenHeaderMissing() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10, 60);
        FakeHttpServletRequest request = new FakeHttpServletRequest().withRemoteAddr("10.0.0.5");

        assertEquals("10.0.0.5", interceptor.getUserId(request));
    }

    @Test
    void getUserIdUsesHeaderWhenPresent() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10, 60);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader("X-User-Id", "explicit-user")
                .withRemoteAddr("10.0.0.5");

        assertEquals("explicit-user", interceptor.getUserId(request));
    }

    @Test
    void blankUserIdHeaderFallsBackToRemoteAddr() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10, 60);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader("X-User-Id", "   ")
                .withRemoteAddr("10.0.0.9");

        assertEquals("10.0.0.9", interceptor.getUserId(request));
    }
}
