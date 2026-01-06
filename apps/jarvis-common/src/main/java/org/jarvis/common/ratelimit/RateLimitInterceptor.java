package org.jarvis.common.ratelimit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Configurable rate limiting interceptor using Guava RateLimiter.
 * Supports per-user and per-endpoint rate limiting.
 * 
 * Usage:
 * <pre>
 * @Configuration
 * public class WebConfig implements WebMvcConfigurer {
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(new RateLimitInterceptor(20, 60)) // 20 req/min
 *                 .addPathPatterns("/api/**");
 *     }
 * }
 * </pre>
 */
@Slf4j
@SuppressWarnings("UnstableApiUsage")
public class RateLimitInterceptor implements HandlerInterceptor {

    private final LoadingCache<String, RateLimiter> limiters;
    private final int requestsPerPeriod;
    private final int periodSeconds;
    private final boolean perEndpoint;

    /**
     * Creates rate limiter with specified limits.
     * 
     * @param requestsPerPeriod Number of requests allowed per period
     * @param periodSeconds Time period in seconds
     */
    public RateLimitInterceptor(int requestsPerPeriod, int periodSeconds) {
        this(requestsPerPeriod, periodSeconds, true);
    }

    /**
     * Creates rate limiter with specified limits.
     * 
     * @param requestsPerPeriod Number of requests allowed per period
     * @param periodSeconds Time period in seconds
     * @param perEndpoint If true, limits are per user+endpoint; if false, per user only
     */
    public RateLimitInterceptor(int requestsPerPeriod, int periodSeconds, boolean perEndpoint) {
        this.requestsPerPeriod = requestsPerPeriod;
        this.periodSeconds = periodSeconds;
        this.perEndpoint = perEndpoint;
        
        double permitsPerSecond = (double) requestsPerPeriod / periodSeconds;
        
        this.limiters = CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .maximumSize(10000)
                .build(new CacheLoader<>() {
                    @Override
                    public RateLimiter load(String key) {
                        return RateLimiter.create(permitsPerSecond);
                    }
                });
        
        log.info("RateLimitInterceptor initialized: {} requests per {} seconds, perEndpoint={}", 
                requestsPerPeriod, periodSeconds, perEndpoint);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String userId = getUserId(request);
        String key = perEndpoint 
                ? userId + ":" + request.getRequestURI()
                : userId;

        try {
            RateLimiter limiter = limiters.get(key);
            if (!limiter.tryAcquire()) {
                log.warn("Rate limit exceeded for key: {} (user: {}, endpoint: {})", 
                        key, userId, request.getRequestURI());
                
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                        "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Please try again later.\",\"retryAfter\":" + periodSeconds + "}"
                );
                return false;
            }
        } catch (ExecutionException e) {
            log.error("Error getting rate limiter for key: {}", key, e);
            // Fail open - allow request if rate limiter fails
        }

        return true;
    }

    /**
     * Extracts user ID from request. Override for custom extraction logic.
     */
    protected String getUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            userId = request.getRemoteAddr();
        }
        return userId;
    }
}

