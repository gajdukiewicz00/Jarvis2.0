package org.jarvis.llm.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    private RestClientConfig config;

    @BeforeEach
    void setUp() {
        config = new RestClientConfig();
        ReflectionTestUtils.setField(config, "llmConnectTimeoutMs", 5000);
        ReflectionTestUtils.setField(config, "llmReadTimeoutMs", 900000);
        ReflectionTestUtils.setField(config, "llmHealthConnectTimeoutMs", 2000);
        ReflectionTestUtils.setField(config, "llmHealthReadTimeoutMs", 2000);
        ReflectionTestUtils.setField(config, "userProfileConnectTimeoutMs", 3000);
        ReflectionTestUtils.setField(config, "userProfileReadTimeoutMs", 5000);
    }

    @Test
    void llmChatRestTemplateUsesApacheHttpClient() {
        RestTemplate restTemplate = config.llmChatRestTemplate();

        assertThat(restTemplate).isNotNull();
        ClientHttpRequestFactory factory = restTemplate.getRequestFactory();
        assertThat(factory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    void llmHealthRestTemplateUsesApacheHttpClient() {
        RestTemplate restTemplate = config.llmHealthRestTemplate();

        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory()).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    void userProfileRestTemplateUsesApacheHttpClient() {
        RestTemplate restTemplate = config.userProfileRestTemplate();

        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory()).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    void defaultRestTemplateUsesApacheHttpClient() {
        RestTemplate restTemplate = config.restTemplate();

        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory()).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }
}
