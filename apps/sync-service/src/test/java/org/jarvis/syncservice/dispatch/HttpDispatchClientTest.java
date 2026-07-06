package org.jarvis.syncservice.dispatch;

import org.jarvis.sync.SyncPayload;
import org.jarvis.sync.SyncPayloadKind;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.dispatch.DispatchClient.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises {@link HttpDispatchClient} against a {@link MockRestServiceServer} bound
 * to the internally-built {@code RestTemplate} (grabbed via {@link ReflectionTestUtils}
 * since the client builds its own template from a {@link RestTemplateBuilder} and does
 * not expose it). Mirrors the MockRestServiceServer pattern already used for other
 * Feign-free HTTP clients in this monorepo (e.g. planner-service's AnalyticsClientTest).
 */
class HttpDispatchClientTest {

    private static final String LIFE_TRACKER_URL = "http://life-tracker:8085";
    private static final String ORCHESTRATOR_URL = "http://orchestrator:8080";

    private HttpDispatchClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        props.setLifeTrackerUrl(LIFE_TRACKER_URL);
        props.setOrchestratorUrl(ORCHESTRATOR_URL);
        props.setDispatchTimeoutMillis(500);

        client = new HttpDispatchClient(new RestTemplateBuilder(), props);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "http");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void dispatchFinanceEntry_success_postsMappedBodyAndHeaders() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/transaction"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-Id", "dev-1"))
                .andExpect(header("X-Client-Nonce", "n-1"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.category").value("coffee"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-1",
                Instant.parse("2026-06-06T10:00:00Z"),
                Map.of("amount", 4.5, "currency", "EUR", "category", "coffee"));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchFinanceEntry_missingOccurredAt_fallsBackToClientOccurredAt() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/transaction"))
                .andExpect(jsonPath("$.occurredAt").exists())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-2",
                Instant.parse("2026-06-06T10:00:00Z"),
                Map.of("amount", 1.0));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchFinanceEntry_occurredAtWithOffsetIsNormalizedAndTruncated() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/transaction"))
                .andExpect(jsonPath("$.occurredAt").value("2026-06-06T15:30:00"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-3",
                Instant.now(),
                Map.of("amount", 1.0, "occurredAt", "2026-06-06T15:30:00.123456+02:00"));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchFinanceEntry_downstreamFailure_returnsFailureResult() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/transaction"))
                .andRespond(withServerError());

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-4",
                Instant.now(), Map.of("amount", 1.0));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isNotBlank();
        server.verify();
    }

    @Test
    void dispatchFinanceEntry_bankNotificationSource_routesToParseNotificationNotTransaction() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/parse-notification"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-Id", "dev-1"))
                .andExpect(header("X-Client-Nonce", "n-bank-1"))
                .andExpect(jsonPath("$.text").value("mBank\nCard payment PLN 12.34 at Zabka"))
                .andExpect(jsonPath("$.store").value(true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-bank-1",
                Instant.parse("2026-06-06T10:00:00Z"),
                Map.of("source", "BANK_NOTIFICATION",
                        "title", "mBank",
                        "description", "Card payment PLN 12.34 at Zabka",
                        "needsReview", true));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchFinanceEntry_bankNotificationSource_neverPostsToTransactionEndpoint() {
        // No expectation is registered for /transaction — if the client mistakenly posts
        // there instead of /parse-notification, MockRestServiceServer.verify() below fails
        // with "no further requests expected" / unmatched request, catching a routing regression.
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/parse-notification"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-bank-2",
                Instant.now(),
                Map.of("source", "BANK_NOTIFICATION", "title", "Revolut", "description", "no amount here"));

        client.dispatchFinanceEntry("dev-1", payload);

        server.verify();
    }

    @Test
    void dispatchFinanceEntry_normalSource_stillPostsToTransaction() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/finance/transaction"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(4.5))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-normal-1",
                Instant.parse("2026-06-06T10:00:00Z"),
                Map.of("amount", 4.5, "currency", "EUR", "category", "coffee"));

        DispatchResult result = client.dispatchFinanceEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchHealthEntry_success_postsMappedBody() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/wellness/health-entry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-Id", "dev-1"))
                .andExpect(jsonPath("$.sleepHours").exists())
                .andExpect(jsonPath("$.steps").exists())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.HEALTH_ENTRY, "n-5",
                Instant.now(), Map.of("sleepHours", 7.5, "steps", 4200));

        DispatchResult result = client.dispatchHealthEntry("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchHealthEntry_downstreamFailure_returnsFailureResult() {
        server.expect(requestTo(LIFE_TRACKER_URL + "/api/v1/life/wellness/health-entry"))
                .andRespond(withServerError());

        SyncPayload payload = new SyncPayload(SyncPayloadKind.HEALTH_ENTRY, "n-6",
                Instant.now(), Map.of());

        DispatchResult result = client.dispatchHealthEntry("dev-1", payload);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isNotBlank();
        server.verify();
    }

    @Test
    void dispatchCommandIntent_success_postsMappedBody() {
        server.expect(requestTo(ORCHESTRATOR_URL + "/api/v1/orchestrator/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Source", "mobile"))
                .andExpect(header("X-User-Id", "dev-1"))
                .andExpect(jsonPath("$.text").value("turn on lights"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        SyncPayload payload = new SyncPayload(SyncPayloadKind.COMMAND_INTENT, "n-7",
                Instant.now(), Map.of("text", "turn on lights", "language", "en"));

        DispatchResult result = client.dispatchCommandIntent("dev-1", payload);

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void dispatchCommandIntent_downstreamFailure_returnsFailureResult() {
        server.expect(requestTo(ORCHESTRATOR_URL + "/api/v1/orchestrator/execute"))
                .andRespond(withServerError());

        SyncPayload payload = new SyncPayload(SyncPayloadKind.COMMAND_INTENT, "n-8",
                Instant.now(), Map.of("text", "stop"));

        DispatchResult result = client.dispatchCommandIntent("dev-1", payload);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isNotBlank();
        server.verify();
    }
}
