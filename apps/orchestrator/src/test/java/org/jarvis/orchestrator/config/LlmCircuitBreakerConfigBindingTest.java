package org.jarvis.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true")
class LlmCircuitBreakerConfigBindingTest {

    @Autowired
    private Environment environment;

    @Test
    void shouldExposeLlmCircuitBreakerPropertiesUnderJarvisLlmNamespace() {
        assertThat(environment.containsProperty("jarvis.llm.circuit-breaker.failure-threshold")).isTrue();
        assertThat(environment.containsProperty("jarvis.llm.circuit-breaker.reset-timeout-seconds")).isTrue();
        assertThat(environment.getProperty("jarvis.llm.circuit-breaker.failure-threshold", Integer.class)).isNotNull();
        assertThat(environment.getProperty("jarvis.llm.circuit-breaker.reset-timeout-seconds", Integer.class)).isNotNull();
    }

    @Test
    void shouldNotExposeLegacyCircuitBreakerNamespace() {
        assertThat(environment.containsProperty("jarvis.circuit-breaker.failure-threshold")).isFalse();
        assertThat(environment.containsProperty("jarvis.circuit-breaker.reset-timeout-seconds")).isFalse();
    }
}
