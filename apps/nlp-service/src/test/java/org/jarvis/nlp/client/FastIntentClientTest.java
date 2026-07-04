package org.jarvis.nlp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link FastIntentClient} builds its own internal {@link RestTemplate} in the
 * constructor (it is never injected), so tests bind a {@link MockRestServiceServer}
 * to that internal instance via reflection instead of mocking a collaborator.
 */
class FastIntentClientTest {

    private static final String URL = "http://fake-llm-service/api/v1/llm/intent";

    private MockRestServiceServer bindServer(FastIntentClient client) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void classifyReturnsEmptyWhenFeatureDisabled() {
        FastIntentClient client = new FastIntentClient(false, URL, 2000L);

        Optional<String> result = client.classify("привет", "ru", List.of("hello"));

        assertTrue(result.isEmpty());
    }

    @Test
    void classifyReturnsEmptyWhenTextIsNull() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);

        assertTrue(client.classify(null, "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsEmptyWhenTextIsBlank() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);

        assertTrue(client.classify("   ", "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsRoutedIntentWhenSourceIsRouterAndIncludesLanguageAndCandidates() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.text").value("привет"))
                .andExpect(jsonPath("$.language").value("ru"))
                .andExpect(jsonPath("$.candidates[0]").value("hello"))
                .andRespond(withSuccess("{\"source\":\"router\",\"intent\":\"hello\"}", MediaType.APPLICATION_JSON));

        Optional<String> result = client.classify("привет", "ru", List.of("hello"));

        assertEquals(Optional.of("hello"), result);
        server.verify();
    }

    @Test
    void classifyOmitsLanguageAndCandidatesWhenNotProvided() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.language").doesNotExist())
                .andExpect(jsonPath("$.candidates").doesNotExist())
                .andRespond(withSuccess("{\"source\":\"router\",\"intent\":\"hello\"}", MediaType.APPLICATION_JSON));

        Optional<String> result = client.classify("привет", null, List.of());

        assertEquals(Optional.of("hello"), result);
        server.verify();
    }

    @Test
    void classifyReturnsEmptyWhenSourceIsNotRouter() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"source\":\"fallback\",\"intent\":\"hello\"}", MediaType.APPLICATION_JSON));

        assertTrue(client.classify("привет", "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsEmptyWhenIntentIsBlank() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"source\":\"router\",\"intent\":\"\"}", MediaType.APPLICATION_JSON));

        assertTrue(client.classify("привет", "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsEmptyWhenResponseBodyIsNull() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertTrue(client.classify("привет", "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsEmptyOnServerError() {
        FastIntentClient client = new FastIntentClient(true, URL, 2000L);
        MockRestServiceServer server = bindServer(client);

        server.expect(requestTo(URL)).andRespond(withServerError());

        assertTrue(client.classify("привет", "ru", null).isEmpty());
    }

    @Test
    void classifyReturnsEmptyWhenHostIsUnreachable() {
        // No MockRestServiceServer here: a real connection attempt to a closed
        // local port triggers a genuine ResourceAccessException, exercising the
        // catch block that wraps I/O failures rather than HTTP-status failures.
        FastIntentClient client = new FastIntentClient(true, "http://127.0.0.1:1/intent", 500L);

        assertTrue(client.classify("привет", "ru", null).isEmpty());
    }

    @Test
    void isEnabledAndGetUrlExposeConfiguredValues() {
        FastIntentClient client = new FastIntentClient(true, URL, 1234L);

        assertTrue(client.isEnabled());
        assertEquals(URL, client.getUrl());
    }

    @Test
    void isEnabledReturnsFalseWhenDisabled() {
        FastIntentClient client = new FastIntentClient(false, URL, 1234L);

        assertFalse(client.isEnabled());
    }
}
