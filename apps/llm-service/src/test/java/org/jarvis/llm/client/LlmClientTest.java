package org.jarvis.llm.client;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmClientTest {

    @Test
    void chatUsesLlamaCppOpenAiCompatibleCompletionsEndpoint() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chatRestTemplate).build();
        LlmClient client = new LlmClient(
                chatRestTemplate,
                healthRestTemplate,
                "http://host-model-daemon:18080",
                true,
                "qwen-local",
                "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-ID", "corr-1"))
                .andExpect(content().json("""
                        {
                          "model": "qwen-local",
                          "stream": false,
                          "messages": [
                            {
                              "role": "user",
                              "content": "hello /no_think"
                            }
                          ],
                          "max_tokens": 64,
                          "temperature": 0.2
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-test",
                          "model": "qwen-local",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "hello back"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 3,
                            "completion_tokens": 2,
                            "total_tokens": 5
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatResponseDto response = client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hello")),
                64,
                0.2,
                "corr-1");

        assertThat(response.getReply()).isEqualTo("hello back");
        assertThat(response.getModel()).isEqualTo("qwen-local");
        assertThat(response.getTokens()).containsEntry("prompt", 3);
        assertThat(response.getTokens()).containsEntry("completion", 2);
        assertThat(response.getTokens()).containsEntry("total", 5);
        server.verify();
    }

    @Test
    void healthReportsReachableHostDaemonAndLocalModelProfile() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(healthRestTemplate)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = new LlmClient(
                chatRestTemplate,
                healthRestTemplate,
                "http://host-model-daemon:18080",
                true,
                "qwen-configured",
                "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "status": "ok"
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://host-model-daemon:18080/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            {
                              "id": "qwen-local"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        LlmClient.LlmServerHealth health = client.getHealth();

        assertThat(health.available()).isTrue();
        assertThat(health.status()).isEqualTo("ok");
        assertThat(health.backend()).isEqualTo("llamacpp-openai");
        assertThat(health.modelLoaded()).isTrue();
        assertThat(health.modelName()).isEqualTo("qwen-local");
        assertThat(health.diagnostics()).containsEntry("runtime", "host-model-daemon");
        assertThat(health.diagnostics()).containsEntry(
                "chat_completions_url",
                "http://host-model-daemon:18080/v1/chat/completions");
        assertThat(health.diagnostics()).containsEntry("model_profile", "qwen-local");
        server.verify();
    }

    @Test
    void chatThrowsWhenClientDisabled() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                false, "qwen-local", "/models/qwen.gguf");

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 64, 0.2, "corr-disabled"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void chatThrowsImmediatelyOn4xxWithoutRetry() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chatRestTemplate).build();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                true, "qwen-local", "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad request"));

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 64, 0.2, "corr-4xx"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("Invalid request");
        server.verify();
    }

    @Test
    void chatRetriesOnceOn5xxThenFailsAfterExhaustingRetries() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chatRestTemplate).build();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                true, "qwen-local", "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        server.expect(requestTo("http://host-model-daemon:18080/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 64, 0.2, "corr-5xx"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("Failed to get response");
        server.verify();
    }

    @Test
    void healthReturnsDisabledWhenClientDisabled() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                false, "qwen-local", "/models/qwen.gguf");

        LlmClient.LlmServerHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("disabled");
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void healthReturnsFalseOnInvalidResponse() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(healthRestTemplate).build();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                true, "qwen-local", "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/health"))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        LlmClient.LlmServerHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("invalid-response");
    }

    @Test
    void healthReturnsErrorOnException() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(healthRestTemplate)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                true, "qwen-local", "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/health"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        LlmClient.LlmServerHealth health = client.getHealth();

        assertThat(health.available()).isFalse();
        assertThat(health.status()).isEqualTo("error");
        assertThat(health.error()).isNotBlank();
    }

    @Test
    void healthDegradesGracefullyWhenModelsEndpointFails() {
        RestTemplate chatRestTemplate = new RestTemplate();
        RestTemplate healthRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(healthRestTemplate)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = new LlmClient(
                chatRestTemplate, healthRestTemplate, "http://host-model-daemon:18080",
                true, "qwen-configured", "/models/qwen.gguf");

        server.expect(requestTo("http://host-model-daemon:18080/health"))
                .andRespond(withSuccess("""
                        { "status": "ok" }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://host-model-daemon:18080/v1/models"))
                .andRespond(withServerError());

        LlmClient.LlmServerHealth health = client.getHealth();

        assertThat(health.available()).isTrue();
        assertThat(health.modelName()).isEqualTo("qwen-configured");
        assertThat(health.diagnostics()).containsKey("models_error");
        server.verify();
    }
}
