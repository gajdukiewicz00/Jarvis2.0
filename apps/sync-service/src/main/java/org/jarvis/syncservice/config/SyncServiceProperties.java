package org.jarvis.syncservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 12 — declarative configuration for sync-service.
 *
 * <p>Defaults match {@code application.yml}; everything can be overridden
 * via {@code JARVIS_SYNC_*} env vars in the k8s manifest.</p>
 */
@ConfigurationProperties(prefix = "jarvis.sync")
public class SyncServiceProperties {

    private String serverKeyPath = "/var/lib/jarvis/sync/server-keys.json";
    private int replayCacheSizePerDevice = 4096;
    private long pairingNonceTtlSeconds = 120;
    private String lifeTrackerUrl = "http://life-tracker:8085";
    private String orchestratorUrl = "http://orchestrator:8080";
    private long dispatchTimeoutMillis = 1500;

    public String getServerKeyPath() { return serverKeyPath; }
    public void setServerKeyPath(String v) { this.serverKeyPath = v; }
    public int getReplayCacheSizePerDevice() { return replayCacheSizePerDevice; }
    public void setReplayCacheSizePerDevice(int v) { this.replayCacheSizePerDevice = v; }
    public long getPairingNonceTtlSeconds() { return pairingNonceTtlSeconds; }
    public void setPairingNonceTtlSeconds(long v) { this.pairingNonceTtlSeconds = v; }
    public String getLifeTrackerUrl() { return lifeTrackerUrl; }
    public void setLifeTrackerUrl(String v) { this.lifeTrackerUrl = v; }
    public String getOrchestratorUrl() { return orchestratorUrl; }
    public void setOrchestratorUrl(String v) { this.orchestratorUrl = v; }
    public long getDispatchTimeoutMillis() { return dispatchTimeoutMillis; }
    public void setDispatchTimeoutMillis(long v) { this.dispatchTimeoutMillis = v; }
}
