package org.jarvis.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 12 — plaintext that the AEAD seals into a {@link SyncEnvelope}.
 *
 * <p>The payload is JSON. {@link SyncPayloadKind} discriminates how
 * sync-service dispatches the {@code data} map. Each payload also carries
 * a {@code clientNonce} that sync-service uses for idempotency on top of
 * the cryptographic nonce.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SyncPayload {

    private final SyncPayloadKind kind;
    private final String clientNonce;
    private final Instant clientOccurredAt;
    private final Map<String, Object> data;

    @JsonCreator
    public SyncPayload(
            @JsonProperty("kind") SyncPayloadKind kind,
            @JsonProperty("clientNonce") String clientNonce,
            @JsonProperty("clientOccurredAt") Instant clientOccurredAt,
            @JsonProperty("data") Map<String, Object> data) {
        this.kind = kind != null ? kind : SyncPayloadKind.UNKNOWN;
        this.clientNonce = Objects.requireNonNull(clientNonce, "clientNonce");
        this.clientOccurredAt = clientOccurredAt != null ? clientOccurredAt : Instant.now();
        this.data = data != null ? Map.copyOf(data) : Map.of();
    }

    public SyncPayloadKind getKind() { return kind; }
    public String getClientNonce() { return clientNonce; }
    public Instant getClientOccurredAt() { return clientOccurredAt; }
    public Map<String, Object> getData() { return data; }
}
