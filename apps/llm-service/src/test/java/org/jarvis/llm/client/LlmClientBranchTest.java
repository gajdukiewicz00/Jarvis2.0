package org.jarvis.llm.client;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Branch/edge-path coverage for {@link LlmClient} that complements
 * {@code LlmClientTest}: retry-then-success on connection errors, timeout fast
 * fail, connection-error exhaustion, response-mapping edge cases, string-typed
 * usage tokens, and health-response variants.
 */
class LlmClientBranchTest {

    private static final String BASE = "http://host-model-daemon:18080";
    private static final String CHAT_URL = BASE + "/v1/chat/completions";
    private static final String HEALTH_URL = BASE + "/health";
    private static final String MODELS_URL = BASE + "/v1/models";

    private LlmClient newClient(RestTemplate chat, RestTemplate health) {
        return new LlmClient(chat, health, BASE, true, "qwen-configured", "/models/qwen.gguf");
    }

    @Test
    void chatRetriesOnConnectionErrorThenSucceeds() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });
        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "model": "qwen-runtime",
                          "choices": [
                            { "message": { "role": "assistant", "content": "recovered" } }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatResponseDto response = client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-conn-retry");

        assertThat(response.getReply()).isEqualTo("recovered");
        assertThat(response.getModel()).isEqualTo("qwen-runtime");
        assertThat(response.getTokens()).isNull();
        server.verify();
    }

    @Test
    void chatFailsFastOnTimeoutWithoutRetry() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-timeout"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("timed out");
        server.verify();
    }

    @Test
    void chatExhaustsRetriesOnConnectionErrors() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });
        server.expect(requestTo(CHAT_URL)).andRespond(request -> {
            throw new ConnectException("Connection refused");
        });

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-conn-fail"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("Failed to get response");
        server.verify();
    }

    @Test
    void chatWrapsMissingContentAsUnexpectedError() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        // 2xx body whose choice has no message.content, no text, and no top-level reply
        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        { "choices": [ { "message": { "role": "assistant" } } ] }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-nocontent"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("Unexpected error");
        server.verify();
    }

    @Test
    void chatRetriesThenFailsOnEmptyBody() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.OK));
        server.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.OK));

        assertThatThrownBy(() -> client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-empty"))
                .isInstanceOf(LlmClient.LlmClientException.class)
                .hasMessageContaining("Failed to get response");
        server.verify();
    }

    @Test
    void chatFallsBackToChoiceTextField() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        { "choices": [ { "text": "legacy completion text" } ] }
                        """, MediaType.APPLICATION_JSON));

        ChatResponseDto response = client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-text");

        assertThat(response.getReply()).isEqualTo("legacy completion text");
        // no model in body -> configured model used
        assertThat(response.getModel()).isEqualTo("qwen-configured");
        server.verify();
    }

    @Test
    void chatFallsBackToTopLevelReplyField() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        { "reply": "flat reply" }
                        """, MediaType.APPLICATION_JSON));

        ChatResponseDto response = client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-reply");

        assertThat(response.getReply()).isEqualTo("flat reply");
        server.verify();
    }

    @Test
    void chatParsesStringTypedUsageTokensAndSkipsUnparseable() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        {
                          "choices": [ { "message": { "content": "ok" } } ],
                          "usage": {
                            "prompt_tokens": "7",
                            "completion_tokens": "not-a-number",
                            "total_tokens": 9
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatResponseDto response = client.chat(
                List.of(new ChatMessageDto(ChatMessageDto.Role.USER, "hi")), 32, 0.1, "corr-tokens");

        assertThat(response.getTokens()).containsEntry("prompt", 7);
        assertThat(response.getTokens()).containsEntry("total", 9);
        assertThat(response.getTokens()).doesNotContainKey("completion");
        server.verify();
    }

    @Test
    void chatHandlesNullContentAndPreExistingNoThinkAndNoOptionalParams() {
        RestTemplate chat = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(chat).build();
        LlmClient client = newClient(chat, new RestTemplate());

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "choices": [ { "message": { "content": "done" } } ] }
                        """, MediaType.APPLICATION_JSON));

        // last user message already carries /no_think; a null-content system message
        // exercises the null-content branch; maxTokens+temperature null skip both ifs.
        List<ChatMessageDto> messages = Arrays.asList(
                new ChatMessageDto(ChatMessageDto.Role.SYSTEM, null),
                new ChatMessageDto(ChatMessageDto.Role.USER, "already /no_think"));

        ChatResponseDto response = client.chat(messages, null, null, null);

        assertThat(response.getReply()).isEqualTo("done");
        server.verify();
    }

    @Test
    void healthUsesModelLoadedFlagAndDeviceFieldsWithStringGpuFlag() {
        RestTemplate health = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(health)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = newClient(new RestTemplate(), health);

        // status "loading" is NOT a healthy status, but model_loaded=true keeps it available
        server.expect(requestTo(HEALTH_URL))
                .andRespond(withSuccess("""
                        {
                          "status": "loading",
                          "model_loaded": true,
                          "device": "cuda:0",
                          "gpu_available": "true",
                          "cuda_version": "12.4"
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(MODELS_URL))
                .andRespond(withSuccess("""
                        { "object": "list", "data": [ { "id": "qwen-runtime" } ] }
                        """, MediaType.APPLICATION_JSON));

        LlmClient.LlmServerHealth health1 = client.getHealth();

        assertThat(health1.available()).isTrue();
        assertThat(health1.modelLoaded()).isTrue();
        assertThat(health1.device()).isEqualTo("cuda:0");
        assertThat(health1.gpuAvailable()).isTrue();
        assertThat(health1.cudaVersion()).isEqualTo("12.4");
        assertThat(health1.modelName()).isEqualTo("qwen-runtime");
        server.verify();
    }

    @Test
    void healthAcceptsPlainTextStatusBodyAndMissingModelsData() {
        RestTemplate health = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(health)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = newClient(new RestTemplate(), health);

        // Non-JSON body: status derived from the raw plain-text body ("OK").
        server.expect(requestTo(HEALTH_URL))
                .andRespond(withSuccess("OK", MediaType.TEXT_PLAIN));
        // models endpoint returns an object without a data[] array.
        server.expect(requestTo(MODELS_URL))
                .andRespond(withSuccess("""
                        { "object": "list" }
                        """, MediaType.APPLICATION_JSON));

        LlmClient.LlmServerHealth health1 = client.getHealth();

        assertThat(health1.available()).isTrue();
        assertThat(health1.status()).isEqualTo("OK");
        // no model discovered -> falls back to configured model
        assertThat(health1.modelName()).isEqualTo("qwen-configured");
        assertThat(health1.diagnostics()).containsKey("models_error");
        server.verify();
    }

    @Test
    void healthDefaultsToReachableWhenStatusAbsent() {
        RestTemplate health = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(health)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = newClient(new RestTemplate(), health);

        // JSON object with no "status" key -> statusValue defaults to "reachable".
        server.expect(requestTo(HEALTH_URL))
                .andRespond(withSuccess("""
                        { "device": "cpu" }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(MODELS_URL))
                .andRespond(withSuccess("""
                        { "data": [ { "id": "qwen-runtime" } ] }
                        """, MediaType.APPLICATION_JSON));

        LlmClient.LlmServerHealth health1 = client.getHealth();

        assertThat(health1.available()).isTrue();
        assertThat(health1.status()).isEqualTo("reachable");
        assertThat(health1.diagnostics()).containsEntry("openai_model_count", 1);
        server.verify();
    }

    @Test
    void isHealthyDelegatesToGetHealth() {
        RestTemplate health = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(health)
                .ignoreExpectOrder(true)
                .build();
        LlmClient client = newClient(new RestTemplate(), health);

        server.expect(requestTo(HEALTH_URL))
                .andRespond(withSuccess("{ \"status\": \"healthy\" }", MediaType.APPLICATION_JSON));
        server.expect(requestTo(MODELS_URL))
                .andRespond(withSuccess("{ \"data\": [] }", MediaType.APPLICATION_JSON));

        assertThat(client.isHealthy()).isTrue();
        server.verify();
    }

    @Test
    void providerMetadataReportsLocalLlamaCpp() {
        LlmClient client = newClient(new RestTemplate(), new RestTemplate());

        assertThat(client.providerName()).isEqualTo("llama-cpp");
        assertThat(client.isLocal()).isTrue();
        assertThat(client.allowsSensitiveData()).isTrue();
    }
}
