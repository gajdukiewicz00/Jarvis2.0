package org.jarvis.llm.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExternalApiLlmProviderTest {

    private static final String API_KEY = "sk-secret-key-do-not-log-1234567890";
    private static final String URL = "https://api.example.com/v1/chat/completions";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ListAppender<ILoggingEvent> logAppender;
    private ExternalApiLlmProvider provider;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);

        // capture logs from before construction so the init log is included
        Logger logger = (Logger) LoggerFactory.getLogger(ExternalApiLlmProvider.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);

        provider = new ExternalApiLlmProvider(builder, "https://api.example.com", "gpt-4o-mini", API_KEY);
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void sendsCorrectRequestShapeAndMapsResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Привет"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"Hi, sir.\"}}]}",
                        MediaType.APPLICATION_JSON));

        ChatResponseDto r = provider.chat(List.of(new ChatMessageDto("user", "Привет")), 100, 0.5, "c1");

        assertThat(r.getReply()).isEqualTo("Hi, sir.");
        assertThat(r.getModel()).isEqualTo("gpt-4o-mini");
        server.verify();
    }

    @Test
    void apiKeyIsNeverLogged() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        provider.chat(List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c2");

        boolean leaked = logAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage() != null && e.getFormattedMessage().contains(API_KEY));
        assertThat(leaked).as("API key must never appear in logs").isFalse();
    }

    @Test
    void externalIsNotLocalAndDeniesSensitive() {
        assertThat(provider.isLocal()).isFalse();
        assertThat(provider.allowsSensitiveData()).isFalse();
    }

    @Test
    void failsSafelyOnHttpError() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(List.of(new ChatMessageDto("user", "hi")), 10, 0.1, "c3"))
                .isInstanceOf(LlmClient.LlmClientException.class);
    }
}
