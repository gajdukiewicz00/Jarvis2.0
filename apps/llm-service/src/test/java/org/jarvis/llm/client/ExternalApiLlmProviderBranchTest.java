package org.jarvis.llm.client;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Branch/edge-path coverage for {@link ExternalApiLlmProvider} that complements
 * {@code ExternalApiLlmProviderTest}: not-configured guard, health flag,
 * non-HTTP failure wrapping, response-content extraction edge cases, and
 * optional-parameter branches.
 */
class ExternalApiLlmProviderBranchTest {

    private static final String API_KEY = "sk-secret-key-do-not-log-1234567890";
    private static final String BASE_URL = "https://api.example.com";
    private static final String URL = BASE_URL + "/v1/chat/completions";

    /** Builds a provider whose internal RestTemplate is the supplied instance. */
    private ExternalApiLlmProvider providerWith(RestTemplate restTemplate, String baseUrl, String apiKey) {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        return new ExternalApiLlmProvider(builder, baseUrl, "gpt-4o-mini", apiKey);
    }

    @Test
    void isHealthyTrueWhenBaseUrlAndApiKeyPresent() {
        ExternalApiLlmProvider provider = providerWith(new RestTemplate(), BASE_URL, API_KEY);

        assertThat(provider.isHealthy()).isTrue();
        assertThat(provider.providerName()).isEqualTo("external");
    }

    @Test
    void isHealthyFalseWhenApiKeyMissing() {
        ExternalApiLlmProvider provider = providerWith(new RestTemplate(), BASE_URL, "");

        assertThat(provider.isHealthy()).isFalse();
    }

    @Test
    void isHealthyFalseWhenBaseUrlMissing() {
        ExternalApiLlmProvider provider = providerWith(new RestTemplate(), "", API_KEY);

        assertThat(provider.isHealthy()).isFalse();
    }

    @Test
    void chatThrowsWhenNotConfigured() {
        ExternalApiLlmProvider provider = providerWith(new RestTemplate(), "", "");

        assertThatThrownBy(() -> provider.chat(
                List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c-unconfigured"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("not set");
    }

    @Test
    void chatWrapsNonHttpFailureAsRequestFailed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalApiLlmProvider provider = providerWith(restTemplate, BASE_URL, API_KEY);

        server.expect(requestTo(URL)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThatThrownBy(() -> provider.chat(
                List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c-conn"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("request failed");
        server.verify();
    }

    @Test
    void chatThrowsWhenResponseBodyIsNull() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalApiLlmProvider provider = providerWith(restTemplate, BASE_URL, API_KEY);

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.OK));

        assertThatThrownBy(() -> provider.chat(
                List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c-nullbody"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("missing choices[0].message.content");
        server.verify();
    }

    @Test
    void chatThrowsWhenChoicesEmpty() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalApiLlmProvider provider = providerWith(restTemplate, BASE_URL, API_KEY);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("{ \"choices\": [] }", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.chat(
                List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c-emptychoices"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("missing choices[0].message.content");
        server.verify();
    }

    @Test
    void chatThrowsWhenMessageHasNoContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalApiLlmProvider provider = providerWith(restTemplate, BASE_URL, API_KEY);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("{ \"choices\": [ { \"message\": {} } ] }",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.chat(
                List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c-nocontent"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("missing choices[0].message.content");
        server.verify();
    }

    @Test
    void chatSucceedsWithoutOptionalParamsAndNullMessageContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalApiLlmProvider provider = providerWith(restTemplate, BASE_URL, API_KEY);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
                        MediaType.APPLICATION_JSON));

        // maxTokens=null and temperature=null skip both optional-param branches;
        // a null-content message exercises the null->"" content mapping.
        ChatResponseDto response = provider.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, null)), null, null, "c-minimal");

        assertThat(response.getReply()).isEqualTo("pong");
        assertThat(response.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(response.getTokens()).isNull();
        server.verify();
    }
}
