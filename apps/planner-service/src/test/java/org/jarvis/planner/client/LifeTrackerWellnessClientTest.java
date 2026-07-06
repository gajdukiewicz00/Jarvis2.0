package org.jarvis.planner.client;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.WellnessSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LifeTrackerWellnessClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LifeTrackerWellnessClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new LifeTrackerWellnessClient(restTemplate, "http://life-tracker:8085");
    }

    @Test
    void fetchSignalCombinesSleepAndStepsAndDerivesLowEnergyFromShortSleep() {
        server.expect(requestTo("http://life-tracker:8085/api/v1/life/wellness/summary?type=SLEEP"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "user-123"))
                .andRespond(withSuccess("""
                        {"type":"SLEEP","entryCount":3,"average":6.0,"latest":6.0}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://life-tracker:8085/api/v1/life/wellness/summary?type=STEPS"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "user-123"))
                .andRespond(withSuccess("""
                        {"type":"STEPS","entryCount":3,"average":4000.0,"latest":3500.0}
                        """, MediaType.APPLICATION_JSON));

        WellnessSignal signal = client.fetchSignal("user-123");

        assertThat(signal.sleepHours()).isEqualTo(6.0);
        assertThat(signal.steps()).isEqualTo(3500);
        assertThat(signal.energy()).isEqualTo(EnergyLevel.LOW);
        server.verify();
    }

    @Test
    void fetchSignalDegradesGracefullyWhenLifeTrackerIsUnavailable() {
        server.expect(requestTo("http://life-tracker:8085/api/v1/life/wellness/summary?type=SLEEP"))
                .andRespond(withServerError());

        WellnessSignal signal = client.fetchSignal("user-123");

        assertThat(signal).isEqualTo(WellnessSignal.neutralDefault());
    }
}
