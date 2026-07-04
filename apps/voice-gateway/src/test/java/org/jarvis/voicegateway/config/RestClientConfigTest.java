package org.jarvis.voicegateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestClientConfigTest {

    private RestClientConfig config;

    @BeforeEach
    void setUp() {
        config = new RestClientConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 1234);
        ReflectionTestUtils.setField(config, "readTimeoutMs", 5678);
    }

    @Test
    void restClientBuilderBuildsAFunctioningRestClientBuilder() {
        RestClient.Builder builder = config.restClientBuilder();

        assertNotNull(builder);
        // Building an actual RestClient exercises the configured HttpComponents request factory.
        RestClient client = builder.build();
        assertNotNull(client);
    }
}
