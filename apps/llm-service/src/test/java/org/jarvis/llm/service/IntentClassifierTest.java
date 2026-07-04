package org.jarvis.llm.service;

import org.jarvis.llm.config.HostModelDaemonProperties;
import org.jarvis.llm.dto.IntentRequest;
import org.jarvis.llm.dto.IntentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IntentClassifierTest {

    private RestTemplate healthRestTemplate;
    private MockRestServiceServer server;
    private HostModelDaemonProperties daemonProperties;
    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        healthRestTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(healthRestTemplate).build();
        daemonProperties = new HostModelDaemonProperties();
        daemonProperties.setHost("host-model-daemon.jarvis-prod.svc.cluster.local");
        classifier = new IntentClassifier(healthRestTemplate, daemonProperties);
    }

    @Test
    void fallsBackWhenDaemonDisabled() {
        daemonProperties.setEnabled(false);
        IntentRequest request = new IntentRequest();
        request.setText("hello");
        request.setCorrelationId("corr-1");

        IntentResponse response = classifier.classify(request);

        assertThat(response.getSource()).isEqualTo("fallback");
        assertThat(response.getReason()).isEqualTo("host-daemon disabled");
        assertThat(response.getConfidence()).isEqualTo(0.0);
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void generatesCorrelationIdWhenBlank() {
        daemonProperties.setEnabled(false);
        IntentRequest request = new IntentRequest();
        request.setText("hello");
        request.setCorrelationId("  ");

        IntentResponse response = classifier.classify(request);

        assertThat(response.getCorrelationId()).isNotBlank();
        assertThat(response.getCorrelationId()).isNotEqualTo("  ");
    }

    @Test
    void classifiesIntentFromRouterChoicesResponse() {
        IntentRequest request = new IntentRequest();
        request.setText("turn on the lights");
        request.setCandidates(List.of("lights_on", "lights_off"));
        request.setLanguage("en");
        request.setCorrelationId("corr-2");

        server.expect(requestTo(
                        "http://host-model-daemon.jarvis-prod.svc.cluster.local:18082/v1/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-ID", "corr-2"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Candidates: lights_on, lights_off")))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            { "text": "lights_on" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        IntentResponse response = classifier.classify(request);

        assertThat(response.getIntent()).isEqualTo("lights_on");
        assertThat(response.getSource()).isEqualTo("router");
        assertThat(response.getConfidence()).isEqualTo(0.7);
        server.verify();
    }

    @Test
    void classifiesIntentFromPlainTextField() {
        IntentRequest request = new IntentRequest();
        request.setText("hello");
        request.setCorrelationId("corr-3");

        server.expect(requestTo(
                        "http://host-model-daemon.jarvis-prod.svc.cluster.local:18082/v1/completions"))
                .andRespond(withSuccess("""
                        { "text": "greeting" }
                        """, MediaType.APPLICATION_JSON));

        IntentResponse response = classifier.classify(request);

        assertThat(response.getIntent()).isEqualTo("greeting");
        assertThat(response.getSource()).isEqualTo("router");
    }

    @Test
    void emptyIntentYieldsZeroConfidence() {
        IntentRequest request = new IntentRequest();
        request.setText("???");
        request.setCorrelationId("corr-4");

        server.expect(requestTo(
                        "http://host-model-daemon.jarvis-prod.svc.cluster.local:18082/v1/completions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        IntentResponse response = classifier.classify(request);

        assertThat(response.getIntent()).isEmpty();
        assertThat(response.getConfidence()).isEqualTo(0.0);
        assertThat(response.getSource()).isEqualTo("router");
    }

    @Test
    void fallsBackWhenDaemonUnreachable() {
        IntentRequest request = new IntentRequest();
        request.setText("hello");
        request.setCorrelationId("corr-5");

        server.expect(requestTo(
                        "http://host-model-daemon.jarvis-prod.svc.cluster.local:18082/v1/completions"))
                .andRespond(request1 -> {
                    throw new java.io.IOException("connection refused");
                });

        IntentResponse response = classifier.classify(request);

        assertThat(response.getSource()).isEqualTo("fallback");
        assertThat(response.getReason()).contains("daemon-unreachable");
    }

    @Test
    void fallsBackOnServerError() {
        IntentRequest request = new IntentRequest();
        request.setText("hello");
        request.setCorrelationId("corr-6");

        server.expect(requestTo(
                        "http://host-model-daemon.jarvis-prod.svc.cluster.local:18082/v1/completions"))
                .andRespond(withServerError());

        IntentResponse response = classifier.classify(request);

        assertThat(response.getSource()).isEqualTo("fallback");
        assertThat(response.getReason()).contains("daemon-error");
    }
}
