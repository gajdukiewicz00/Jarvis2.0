package org.jarvis.common.http;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Factory for creating RestTemplate instances with proper timeout configuration.
 * Uses Apache HttpClient5 for reliable timeout handling.
 * 
 * Usage:
 * <pre>
 * RestTemplate restTemplate = RestTemplateFactory.create()
 *     .connectTimeout(5000)
 *     .readTimeout(30000)
 *     .maxConnections(50)
 *     .build();
 * </pre>
 */
@Slf4j
public class RestTemplateFactory {

    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    private int maxConnTotal = 50;
    private int maxConnPerRoute = 20;
    private String name = "default";

    private RestTemplateFactory() {
    }

    public static RestTemplateFactory create() {
        return new RestTemplateFactory();
    }

    public RestTemplateFactory name(String name) {
        this.name = name;
        return this;
    }

    public RestTemplateFactory connectTimeout(int ms) {
        this.connectTimeoutMs = ms;
        return this;
    }

    public RestTemplateFactory readTimeout(int ms) {
        this.readTimeoutMs = ms;
        return this;
    }

    public RestTemplateFactory maxConnections(int total) {
        this.maxConnTotal = total;
        return this;
    }

    public RestTemplateFactory maxConnectionsPerRoute(int perRoute) {
        this.maxConnPerRoute = perRoute;
        return this;
    }

    public RestTemplate build() {
        // Connection config
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .setSocketTimeout(Timeout.of(readTimeoutMs, TimeUnit.MILLISECONDS))
                .build();

        // Connection manager with pooling
        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(maxConnTotal)
                .setMaxConnPerRoute(maxConnPerRoute)
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

        log.info("🔧 RestTemplate '{}' created: connect={}ms, read={}ms, maxConn={}, maxConnPerRoute={}",
                name, connectTimeoutMs, readTimeoutMs, maxConnTotal, maxConnPerRoute);

        return new RestTemplate(requestFactory);
    }

    /**
     * Creates a RestTemplate optimized for health checks (short timeouts).
     */
    public static RestTemplate forHealthChecks(String name) {
        return create()
                .name(name + "-health")
                .connectTimeout(2000)
                .readTimeout(2000)
                .maxConnections(10)
                .maxConnectionsPerRoute(5)
                .build();
    }

    /**
     * Creates a RestTemplate optimized for LLM requests (long timeouts).
     */
    public static RestTemplate forLlm(String name, int readTimeoutMs) {
        return create()
                .name(name + "-llm")
                .connectTimeout(5000)
                .readTimeout(readTimeoutMs)
                .maxConnections(20)
                .maxConnectionsPerRoute(10)
                .build();
    }
}

