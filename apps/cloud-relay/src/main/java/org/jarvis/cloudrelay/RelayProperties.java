package org.jarvis.cloudrelay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jarvis.relay")
public class RelayProperties {
    private int queueCap = 256;
    private int maxBlobBytes = 65536;
    private long blobTtlSeconds = 86400;
    private long queueIdleTtlSeconds = 604800;

    public int getQueueCap() { return queueCap; }
    public void setQueueCap(int v) { this.queueCap = v; }
    public int getMaxBlobBytes() { return maxBlobBytes; }
    public void setMaxBlobBytes(int v) { this.maxBlobBytes = v; }
    public long getBlobTtlSeconds() { return blobTtlSeconds; }
    public void setBlobTtlSeconds(long v) { this.blobTtlSeconds = v; }
    public long getQueueIdleTtlSeconds() { return queueIdleTtlSeconds; }
    public void setQueueIdleTtlSeconds(long v) { this.queueIdleTtlSeconds = v; }
}
