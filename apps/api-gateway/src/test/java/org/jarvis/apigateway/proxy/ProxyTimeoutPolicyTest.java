package org.jarvis.apigateway.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyTimeoutPolicyTest {

    @Test
    void connectTimeoutDefaultsToFiveSecondsForAnyServiceWhenNoOverrideConfigured() {
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(new ProxyTimeoutProperties());

        assertThat(policy.connectTimeout("agent-service")).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.connectTimeout("media-service")).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.connectTimeout("unlisted-service")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void readTimeoutDefaultsToTenSecondsForAnUnlistedService() {
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(new ProxyTimeoutProperties());

        assertThat(policy.readTimeout("unlisted-service")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void agentServiceReadTimeoutDefaultsToSixtySecondsForAwaitCompletionSwarmRuns() {
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(new ProxyTimeoutProperties());

        assertThat(policy.readTimeout("agent-service")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void mediaServiceReadTimeoutDefaultsToThirtySecondsForTheSynchronousProbeEndpoint() {
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(new ProxyTimeoutProperties());

        assertThat(policy.readTimeout("media-service")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void configuredConnectTimeoutOverrideTakesPrecedenceOverTheHardcodedDefault() {
        ProxyTimeoutProperties properties = new ProxyTimeoutProperties();
        properties.setConnectMsByService(Map.of("agent-service", 1_234L));
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(properties);

        assertThat(policy.connectTimeout("agent-service")).isEqualTo(Duration.ofMillis(1_234));
        // Unconfigured services are unaffected by another service's override.
        assertThat(policy.connectTimeout("media-service")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void configuredReadTimeoutOverrideTakesPrecedenceOverTheSwitchDefault() {
        ProxyTimeoutProperties properties = new ProxyTimeoutProperties();
        properties.setReadMsByService(Map.of("media-service", 45_000L));
        ProxyTimeoutPolicy policy = new ProxyTimeoutPolicy(properties);

        assertThat(policy.readTimeout("media-service")).isEqualTo(Duration.ofMillis(45_000));
        // Unconfigured services still fall back to the switch-based default.
        assertThat(policy.readTimeout("agent-service")).isEqualTo(Duration.ofSeconds(60));
    }
}
