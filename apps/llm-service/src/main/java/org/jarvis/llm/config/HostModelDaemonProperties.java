package org.jarvis.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 3 — typed configuration for the host model daemon.
 *
 * <p>Three model channels live behind a single Kubernetes Service
 * {@code host-model-daemon.jarvis-prod.svc.cluster.local} on three named
 * ports. Pods address llama.cpp on the host through this Service. Only
 * llm-service is allowed to call them (SPEC-1 boundary rule).</p>
 *
 * <ul>
 *   <li>{@code main}   — main reasoning brain (Phase 7 voice loop)</li>
 *   <li>{@code coding} — coding-focused brain</li>
 *   <li>{@code router} — fast intent router used by nlp-service</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.llm.host-daemon")
public class HostModelDaemonProperties {

    /** Whether host model daemon routing is active in this environment. */
    private boolean enabled = true;

    /** Cluster service host. Must resolve to the host model daemon. */
    private String host = "host-model-daemon.jarvis-prod.svc.cluster.local";

    private Channel main = new Channel(18080, "/health");
    private Channel coding = new Channel(18081, "/health");
    private Channel router = new Channel(18082, "/health");

    public String urlFor(Channel channel) {
        return "http://" + host + ":" + channel.getPort();
    }

    public String healthUrlFor(Channel channel) {
        return urlFor(channel) + channel.getHealthPath();
    }

    @Getter
    @Setter
    public static class Channel {
        private int port;
        private String healthPath = "/health";

        public Channel() {
        }

        public Channel(int port, String healthPath) {
            this.port = port;
            this.healthPath = healthPath;
        }
    }
}
