package org.jarvis.apigateway.status;

import org.jarvis.apigateway.support.RecordingHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpHealthProbeTest {

    private RecordingHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void isHealthyReturnsFalseForNullBaseUrl() {
        HttpHealthProbe probe = new HttpHealthProbe(1500L, "/actuator/health");
        assertThat(probe.isHealthy(null)).isFalse();
    }

    @Test
    void isHealthyReturnsFalseForBlankBaseUrl() {
        HttpHealthProbe probe = new HttpHealthProbe(1500L, "/actuator/health");
        assertThat(probe.isHealthy("   ")).isFalse();
    }

    @Test
    void isHealthyReturnsTrueFor2xxResponse() {
        server = RecordingHttpServer.start();
        server.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"status\":\"UP\"}"));
        HttpHealthProbe probe = new HttpHealthProbe(2000L, "/actuator/health");

        assertThat(probe.isHealthy(server.baseUrl())).isTrue();
        assertThat(server.lastRequest().path()).isEqualTo("/actuator/health");
    }

    @Test
    void isHealthyReturnsFalseFor5xxResponse() {
        server = RecordingHttpServer.start();
        server.setHandler(request -> RecordingHttpServer.StubResponse.json(500, "{\"status\":\"DOWN\"}"));
        HttpHealthProbe probe = new HttpHealthProbe(2000L, "/actuator/health");

        assertThat(probe.isHealthy(server.baseUrl())).isFalse();
    }

    @Test
    void isHealthyReturnsFalseFor4xxResponse() {
        server = RecordingHttpServer.start();
        server.setHandler(request -> RecordingHttpServer.StubResponse.json(404, "{}"));
        HttpHealthProbe probe = new HttpHealthProbe(2000L, "/actuator/health");

        assertThat(probe.isHealthy(server.baseUrl())).isFalse();
    }

    @Test
    void isHealthyStripsTrailingSlashesFromBaseUrl() {
        server = RecordingHttpServer.start();
        server.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{}"));
        HttpHealthProbe probe = new HttpHealthProbe(2000L, "/actuator/health");

        assertThat(probe.isHealthy(server.baseUrl() + "///")).isTrue();
        assertThat(server.lastRequest().path()).isEqualTo("/actuator/health");
    }

    @Test
    void isHealthyUsesConfiguredHealthPath() {
        server = RecordingHttpServer.start();
        server.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{}"));
        HttpHealthProbe probe = new HttpHealthProbe(2000L, "/healthz");

        assertThat(probe.isHealthy(server.baseUrl())).isTrue();
        assertThat(server.lastRequest().path()).isEqualTo("/healthz");
    }

    @Test
    void isHealthyReturnsFalseWhenConnectionIsRefused() {
        server = RecordingHttpServer.start();
        String baseUrl = server.baseUrl();
        server.close();
        server = null;

        HttpHealthProbe probe = new HttpHealthProbe(500L, "/actuator/health");

        assertThat(probe.isHealthy(baseUrl)).isFalse();
    }
}
