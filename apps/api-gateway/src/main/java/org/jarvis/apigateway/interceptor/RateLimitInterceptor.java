package org.jarvis.apigateway.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@SuppressWarnings("UnstableApiUsage")
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${rate-limit.enabled:false}")
    private boolean rateLimitEnabled;

    @Value("${rate-limit.max-requests-per-minute:100}")
    private int maxRequestsPerMinute;

    private RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!rateLimitEnabled) {
            return true;
        }

        if (rateLimiter == null) {
            // Initialize rate limiter (permits per second)
            double permitsPerSecond = maxRequestsPerMinute / 60.0;
            rateLimiter = RateLimiter.create(permitsPerSecond);
            log.info("Rate limiter initialized: {} requests/minute", maxRequestsPerMinute);
        }

        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for IP: {}", request.getRemoteAddr());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Rate limit exceeded. Please try again later.\"}");
            return false;
        }

        return true;
    }
}
