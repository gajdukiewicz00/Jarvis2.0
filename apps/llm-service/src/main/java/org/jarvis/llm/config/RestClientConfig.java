package org.jarvis.llm.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * REST client configuration with separate timeouts for different use cases.
 * Uses Apache HttpClient5 for reliable timeout handling.
 * 
 * Beans:
 * - llmChatRestTemplate: long read timeout for CPU inference (up to 15 min)
 * - llmHealthRestTemplate: short timeout for health checks (2s)
 * - userProfileRestTemplate: medium timeout for user-profile service (5s)
 */
@Slf4j
@Configuration
public class RestClientConfig {

    // === LLM Chat timeouts (long read for CPU inference) ===
    @Value("${llm.timeouts.connect-ms:5000}")
    private int llmConnectTimeoutMs;

    @Value("${llm.timeouts.read-ms:900000}")
    private int llmReadTimeoutMs;

    // === LLM Health check timeouts (short) ===
    @Value("${llm.health.timeouts.connect-ms:2000}")
    private int llmHealthConnectTimeoutMs;

    @Value("${llm.health.timeouts.read-ms:2000}")
    private int llmHealthReadTimeoutMs;

    // === User Profile service timeouts ===
    @Value("${services.user-profile.timeouts.connect-ms:3000}")
    private int userProfileConnectTimeoutMs;

    @Value("${services.user-profile.timeouts.read-ms:5000}")
    private int userProfileReadTimeoutMs;

    /**
     * RestTemplate for LLM chat requests.
     * Long read timeout to support CPU inference (60-120+ seconds).
     */
    @Bean("llmChatRestTemplate")
    public RestTemplate llmChatRestTemplate() {
        RestTemplate restTemplate = createRestTemplate(
                "llmChatRestTemplate",
                llmConnectTimeoutMs,
                llmReadTimeoutMs
        );
        log.info("🔧 llmChatRestTemplate CREATED: connect={}ms, read={}ms ({}min)",
                llmConnectTimeoutMs, llmReadTimeoutMs, llmReadTimeoutMs / 60000);
        return restTemplate;
    }

    /**
     * RestTemplate for LLM health checks.
     * Short timeout to avoid blocking actuator health.
     */
    @Bean("llmHealthRestTemplate")
    public RestTemplate llmHealthRestTemplate() {
        RestTemplate restTemplate = createRestTemplate(
                "llmHealthRestTemplate",
                llmHealthConnectTimeoutMs,
                llmHealthReadTimeoutMs
        );
        log.info("🔧 llmHealthRestTemplate CREATED: connect={}ms, read={}ms",
                llmHealthConnectTimeoutMs, llmHealthReadTimeoutMs);
        return restTemplate;
    }

    /**
     * RestTemplate for user-profile service.
     * Medium timeout - this is an optional dependency.
     */
    @Bean("userProfileRestTemplate")
    public RestTemplate userProfileRestTemplate() {
        RestTemplate restTemplate = createRestTemplate(
                "userProfileRestTemplate",
                userProfileConnectTimeoutMs,
                userProfileReadTimeoutMs
        );
        log.info("🔧 userProfileRestTemplate CREATED: connect={}ms, read={}ms",
                userProfileConnectTimeoutMs, userProfileReadTimeoutMs);
        return restTemplate;
    }

    /**
     * Primary RestTemplate for general use (fallback).
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = createRestTemplate(
                "defaultRestTemplate",
                5000,
                10000
        );
        log.info("🔧 Default RestTemplate CREATED: connect=5000ms, read=10000ms");
        return restTemplate;
    }

    /**
     * Creates a RestTemplate with Apache HttpClient5 and specified timeouts.
     * This ensures timeouts are reliably enforced.
     */
    private RestTemplate createRestTemplate(String name, int connectTimeoutMs, int readTimeoutMs) {
        // Connection config with connect timeout
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .setSocketTimeout(Timeout.of(readTimeoutMs, TimeUnit.MILLISECONDS))
                .build();

        // Connection manager with pooling
        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();

        // Request config with response timeout
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .setResponseTimeout(Timeout.of(readTimeoutMs, TimeUnit.MILLISECONDS))
                .build();

        // Build HttpClient
        HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        // Create request factory
        HttpComponentsClientHttpRequestFactory requestFactory = 
                new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(connectTimeoutMs);

        log.debug("Created HttpClient for {}: connectTimeout={}ms, readTimeout={}ms",
                name, connectTimeoutMs, readTimeoutMs);

        return new RestTemplate(requestFactory);
    }
}
