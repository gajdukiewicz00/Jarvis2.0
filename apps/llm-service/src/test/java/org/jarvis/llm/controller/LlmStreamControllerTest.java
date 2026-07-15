package org.jarvis.llm.controller;

import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link LlmStreamController}. The controller proxies the local
 * daemon's SSE stream; the real {@link HttpClient} is replaced with a Mockito
 * mock via reflection so no network is touched. Private helpers are exercised
 * directly (with explicit parameter types) to cover the parsing/escaping
 * branches deterministically.
 */
class LlmStreamControllerTest {

    private LlmStreamController controller;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        controller = new LlmStreamController();
        http = mock(HttpClient.class);
        ReflectionTestUtils.setField(controller, "http", http);
        ReflectionTestUtils.setField(controller, "daemonUrl", "http://localhost:5000//");
        ReflectionTestUtils.setField(controller, "timeoutSeconds", 5L);
    }

    private ChatRequestDto chatRequest() {
        ChatMessageDto message = new ChatMessageDto(ChatMessageDto.Role.USER, "Привет");
        return new ChatRequestDto("session-1", List.of(message), 256, 0.5);
    }

    // Resolve a private method by exact signature to avoid ambiguity between
    // helpers that share arity/param types (e.g. extractContent vs jsonString).
    private Method privateMethod(String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method method = LlmStreamController.class.getDeclaredMethod(name, paramTypes);
        method.setAccessible(true);
        return method;
    }

    private Object invoke(Method method, Object... args) throws Exception {
        return method.invoke(controller, args);
    }

    // Wraps a list in a Stream whose exhaustion (final hasNext == false) trips a
    // latch, giving the test a deterministic signal that forEach has finished.
    private Stream<String> latchedStream(List<String> lines, CountDownLatch consumed) {
        Iterator<String> delegate = lines.iterator();
        Iterator<String> hooked = new Iterator<>() {
            @Override
            public boolean hasNext() {
                boolean has = delegate.hasNext();
                if (!has) {
                    consumed.countDown();
                }
                return has;
            }

            @Override
            public String next() {
                return delegate.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(hooked, 0), false);
    }

    // ── stream(): happy path ──────────────────────────────────────────────

    @Test
    void streamForwardsDeltasAndCompletes() throws Exception {
        CountDownLatch consumed = new CountDownLatch(1);
        List<String> lines = new ArrayList<>(List.of(
                "ignored non-data line",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}",
                "data: [DONE]"));

        @SuppressWarnings("unchecked")
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        doReturn(latchedStream(lines, consumed)).when(response).body();
        doReturn(response).when(http).send(any(), any());

        SseEmitter emitter = controller.stream(chatRequest());

        assertThat(emitter).isNotNull();
        assertThat(consumed.await(5, TimeUnit.SECONDS)).isTrue();
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void streamHandlesNullRequestBody() throws Exception {
        CountDownLatch consumed = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        doReturn(latchedStream(new ArrayList<>(List.of("data: [DONE]")), consumed)).when(response).body();
        doReturn(response).when(http).send(any(), any());

        SseEmitter emitter = controller.stream(null);

        assertThat(emitter).isNotNull();
        assertThat(consumed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    // ── stream(): error path ──────────────────────────────────────────────

    @Test
    void streamCompletesWithErrorWhenSendFails() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            throw new IOException("connection refused");
        }).when(http).send(any(), any());

        SseEmitter emitter = controller.stream(chatRequest());

        assertThat(emitter).isNotNull();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        verify(http, times(1)).send(any(), any());
    }

    // ── forwardLine(): branch coverage via reflection ─────────────────────

    @Test
    void forwardLineIgnoresNullAndNonDataLines() throws Exception {
        Method forwardLine = privateMethod("forwardLine", SseEmitter.class, String.class);
        SseEmitter emitter = mock(SseEmitter.class);

        invoke(forwardLine, emitter, null);
        invoke(forwardLine, emitter, "event: ping");

        verify(emitter, times(0)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void forwardLineIgnoresDoneMarker() throws Exception {
        Method forwardLine = privateMethod("forwardLine", SseEmitter.class, String.class);
        SseEmitter emitter = mock(SseEmitter.class);

        invoke(forwardLine, emitter, "data: [DONE]");

        verify(emitter, times(0)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void forwardLineIgnoresEmptyToken() throws Exception {
        Method forwardLine = privateMethod("forwardLine", SseEmitter.class, String.class);
        SseEmitter emitter = mock(SseEmitter.class);

        invoke(forwardLine, emitter, "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}");

        verify(emitter, times(0)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void forwardLineSendsNonEmptyToken() throws Exception {
        Method forwardLine = privateMethod("forwardLine", SseEmitter.class, String.class);
        SseEmitter emitter = mock(SseEmitter.class);

        invoke(forwardLine, emitter, "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}");

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void forwardLineWrapsSendFailureAsRuntimeException() throws Exception {
        Method forwardLine = privateMethod("forwardLine", SseEmitter.class, String.class);
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> invoke(forwardLine, emitter,
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("client disconnected");
    }

    // ── extractContent(): parsing branches ────────────────────────────────

    @Test
    void extractContentReturnsTokenForValidJson() throws Exception {
        Method extractContent = privateMethod("extractContent", String.class);

        Object token = invoke(extractContent, "{\"choices\":[{\"delta\":{\"content\":\"word\"}}]}");

        assertThat(token).isEqualTo("word");
    }

    @Test
    void extractContentReturnsNullForMissingContent() throws Exception {
        Method extractContent = privateMethod("extractContent", String.class);

        Object token = invoke(extractContent, "{}");

        assertThat(token).isNull();
    }

    @Test
    void extractContentReturnsNullForNullContent() throws Exception {
        Method extractContent = privateMethod("extractContent", String.class);

        Object token = invoke(extractContent, "{\"choices\":[{\"delta\":{\"content\":null}}]}");

        assertThat(token).isNull();
    }

    @Test
    void extractContentReturnsNullForMalformedJson() throws Exception {
        Method extractContent = privateMethod("extractContent", String.class);

        Object token = invoke(extractContent, "not-json{");

        assertThat(token).isNull();
    }

    // ── buildDaemonBody(): message assembly ───────────────────────────────

    @Test
    void buildDaemonBodyIncludesPersonaAndMessages() throws Exception {
        Method buildDaemonBody = privateMethod("buildDaemonBody", ChatRequestDto.class);
        ChatMessageDto withRole = new ChatMessageDto(ChatMessageDto.Role.USER, "hello");
        ChatMessageDto blank = new ChatMessageDto(); // null role + null content branches
        ChatRequestDto request = new ChatRequestDto("s", List.of(withRole, blank), 256, 0.5);

        String body = (String) invoke(buildDaemonBody, request);

        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("\"content\":\"hello\"");
        assertThat(body).contains("\"content\":\"\"");
    }

    @Test
    void buildDaemonBodyHandlesNullMessageList() throws Exception {
        Method buildDaemonBody = privateMethod("buildDaemonBody", ChatRequestDto.class);
        ChatRequestDto request = new ChatRequestDto("s", null, 256, 0.5);

        String body = (String) invoke(buildDaemonBody, request);

        assertThat(body).contains("\"role\":\"system\"");
    }

    // ── jsonString(): escaping ────────────────────────────────────────────

    @Test
    void jsonStringEscapesSpecialCharacters() throws Exception {
        Method jsonString = privateMethod("jsonString", String.class);
        String input = "quote\"back\\newline\ntab\treturn\r" + '\u0001' + "plain";

        String escaped = (String) jsonString.invoke(null, input);

        assertThat(escaped)
                .startsWith("\"")
                .endsWith("\"")
                .contains("\\\"")
                .contains("\\\\")
                .contains("\\n")
                .contains("\\t")
                .contains("\\r")
                .contains("\\u0001")
                .contains("plain");
    }

    @Test
    void jsonStringLeavesPlainTextIntact() throws Exception {
        Method jsonString = privateMethod("jsonString", String.class);

        String escaped = (String) jsonString.invoke(null, "plain");

        assertThat(escaped).isEqualTo("\"plain\"");
    }
}
