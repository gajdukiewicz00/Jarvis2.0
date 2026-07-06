package org.jarvis.syncservice.controller;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.service.RecordSyncService.InvalidSyncRecordException;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 12 — inbound request body for {@code POST /api/v1/sync/records}.
 *
 * <p>{@code dedupKey} is the caller's natural/dedup key (e.g.
 * {@code "finance:2026-07-01:txn-882"}); {@code recordId} identifies this
 * specific write so a retried submission of the exact same write is recognised
 * as a duplicate rather than a competing edit. {@code updatedAt} is the client's
 * logical last-write timestamp used to resolve conflicting concurrent edits.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SyncRecordRequest {

    private final String dedupKey;
    private final String recordId;
    private final String deviceId;
    private final Instant updatedAt;
    private final Map<String, Object> payload;

    @JsonCreator
    public SyncRecordRequest(
            @JsonProperty("dedupKey") String dedupKey,
            @JsonProperty("recordId") String recordId,
            @JsonProperty("deviceId") String deviceId,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("payload") Map<String, Object> payload) {
        this.dedupKey = dedupKey;
        this.recordId = recordId;
        this.deviceId = deviceId;
        this.updatedAt = updatedAt;
        this.payload = payload;
    }

    public String getDedupKey() { return dedupKey; }
    public String getRecordId() { return recordId; }
    public String getDeviceId() { return deviceId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Map<String, Object> getPayload() { return payload; }

    /** @throws InvalidSyncRecordException when a required field is missing. */
    public SyncRecord toDomain() {
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new InvalidSyncRecordException("dedupKey is required");
        }
        if (recordId == null || recordId.isBlank()) {
            throw new InvalidSyncRecordException("recordId is required");
        }
        if (updatedAt == null) {
            throw new InvalidSyncRecordException("updatedAt is required");
        }
        return new SyncRecord(dedupKey, recordId, deviceId, updatedAt, payload, 0L);
    }
}
