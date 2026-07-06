package org.jarvis.apigateway.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Regression test for the non-atomic check-then-act lazy-init race in preHandle():
     * concurrent first requests could each observe rateLimiter == null and construct their
     * own RateLimiter, silently discarding one another. This test widens the race window
     * (via an overridden createRateLimiter() that sleeps) so the bug reproduces
     * deterministically: without synchronization/volatile, multiple threads race past the
     * null-check before any assignment happens, so creationCount ends up > 1. With the fix
     * (synchronized double-checked locking + volatile field), only one thread ever
     * constructs the RateLimiter, so creationCount is always exactly 1.
     */
    @Test
    void preHandleCreatesRateLimiterExactlyOnceUnderConcurrentFirstRequests() throws Exception {
        AtomicInteger creationCount = new AtomicInteger(0);
        RateLimitInterceptor racyInterceptor = new RateLimitInterceptor() {
            @Override
            RateLimiter createRateLimiter(double permitsPerSecond) {
                creationCount.incrementAndGet();
                try {
                    // Widen the race window so other racing threads also observe
                    // rateLimiter == null before this thread finishes assigning it.
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return RateLimiter.create(permitsPerSecond);
            }
        };
        ReflectionTestUtils.setField(racyInterceptor, "rateLimitEnabled", true);
        ReflectionTestUtils.setField(racyInterceptor, "maxRequestsPerMinute", 6000);

        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        racyInterceptor.preHandle(
                                new MockHttpServletRequest("GET", "/api/test"),
                                new MockHttpServletResponse(),
                                new Object());
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete within timeout");
        } finally {
            executor.shutdown();
        }

        assertTrue(errors.isEmpty(), "No exceptions expected from concurrent preHandle calls: " + errors);
        assertEquals(1, creationCount.get(),
                "RateLimiter should be constructed exactly once even under concurrent first requests");
    }
}
