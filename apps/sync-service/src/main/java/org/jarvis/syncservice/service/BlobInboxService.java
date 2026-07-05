package org.jarvis.syncservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.sync.SyncEnvelope;
import org.jarvis.sync.SyncPayload;
import org.jarvis.sync.SyncPayloadKind;
import org.jarvis.sync.crypto.SyncCrypto;
import org.jarvis.syncservice.dispatch.DispatchClient;
import org.jarvis.syncservice.dispatch.DispatchClient.DispatchResult;
import org.jarvis.syncservice.domain.PairedDevice;
import org.jarvis.syncservice.repository.PairingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Phase 12 — receives a {@link SyncEnvelope}, validates routing +
 * device-pairing + replay, opens the AEAD, parses the {@link SyncPayload},
 * and dispatches to the right downstream service.
 *
 * <p>Audit emit is fire-and-forget: replay rejects, AEAD failures and
 * dispatch failures all surface to Kafka so the operator's audit log is
 * complete, but they never throw out of this method except as the
 * {@link InboxResult} the controller returns.</p>
 */
@Slf4j
@Service
public class BlobInboxService {

    public enum Status {
        ACCEPTED, UNKNOWN_DEVICE, ROUTING_MISMATCH, REPLAY,
        TAMPERED, DISPATCH_FAILED, UNSUPPORTED_KIND
    }

    public record InboxResult(Status status, String detail) {
        public boolean ok() { return status == Status.ACCEPTED; }
    }

    private final SyncCrypto crypto;
    private final PairingStore pairingStore;
    private final ReplayCache replayCache;
    private final DispatchClient dispatch;
    private final ObjectProvider<AuditPublisher> auditProvider;
    private final SyncMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public BlobInboxService(SyncCrypto crypto,
                            PairingStore pairingStore,
                            ReplayCache replayCache,
                            DispatchClient dispatch,
                            ObjectProvider<AuditPublisher> auditProvider,
                            SyncMetrics metrics) {
        this.crypto = crypto;
        this.pairingStore = pairingStore;
        this.replayCache = replayCache;
        this.dispatch = dispatch;
        this.auditProvider = auditProvider;
        this.metrics = metrics;
    }

    public InboxResult ingest(SyncEnvelope env) {
        PairedDevice device = pairingStore.findByDeviceId(env.getSenderDeviceId()).orElse(null);
        if (device == null) {
            audit(AuditEventType.SYNC_BLOB_TAMPER_REJECTED, env, Map.of("reason", "unknown_device"));
            return result(Status.UNKNOWN_DEVICE, "device not paired");
        }
        if (!device.routingId().equals(env.getRoutingId())) {
            audit(AuditEventType.SYNC_BLOB_TAMPER_REJECTED, env, Map.of("reason", "routing_mismatch"));
            return result(Status.ROUTING_MISMATCH, "routingId does not match pairing");
        }
        if (!replayCache.recordIfUnseen(device.deviceId(), env.getNonceB64())) {
            audit(AuditEventType.SYNC_BLOB_REPLAY_REJECTED, env, Map.of("reason", "nonce_seen"));
            return result(Status.REPLAY, "nonce previously seen");
        }

        byte[] nonce = SyncCrypto.unb64(env.getNonceB64());
        byte[] ciphertext = SyncCrypto.unb64(env.getCiphertextB64());
        byte[] aad = aad(env);
        byte[] plaintext;
        try {
            plaintext = crypto.open(device.sessionKey(), nonce, aad, ciphertext);
        } catch (SyncCrypto.AeadAuthException e) {
            audit(AuditEventType.SYNC_BLOB_TAMPER_REJECTED, env, Map.of("reason", "aead_tag"));
            return result(Status.TAMPERED, "AEAD authentication failed");
        }

        SyncPayload payload;
        try {
            payload = mapper.readValue(plaintext, SyncPayload.class);
        } catch (Exception e) {
            audit(AuditEventType.SYNC_BLOB_TAMPER_REJECTED, env, Map.of("reason", "payload_parse"));
            return result(Status.TAMPERED, "payload JSON malformed");
        }

        device.touch();
        audit(AuditEventType.SYNC_BLOB_RECEIVED, env, Map.of("kind", payload.getKind().name()));

        DispatchResult dr = switch (payload.getKind()) {
            case FINANCE_ENTRY -> dispatch.dispatchFinanceEntry(device.deviceId(), payload);
            case COMMAND_INTENT -> dispatch.dispatchCommandIntent(device.deviceId(), payload);
            case HEALTH_ENTRY -> dispatch.dispatchHealthEntry(device.deviceId(), payload);
            case DEVICE_HEARTBEAT -> DispatchResult.success();
            case UNKNOWN -> DispatchResult.failure("unknown_kind");
        };
        if (payload.getKind() == SyncPayloadKind.FINANCE_ENTRY) {
            metrics.recordBankDraft(confidenceOf(payload), dr.ok());
        }
        if (!dr.ok()) {
            audit(AuditEventType.SYNC_DISPATCH_FAILED, env,
                    Map.of("kind", payload.getKind().name(), "detail", dr.detail()));
            Status s = payload.getKind() == SyncPayloadKind.UNKNOWN
                    ? Status.UNSUPPORTED_KIND : Status.DISPATCH_FAILED;
            return result(s, dr.detail());
        }
        return result(Status.ACCEPTED, null);
    }

    /** Builds the result and records the {@code sync.events} counter for its outcome in one place. */
    private InboxResult result(Status status, String detail) {
        metrics.recordEvent(status.name().toLowerCase(java.util.Locale.ROOT));
        return new InboxResult(status, detail);
    }

    /** Opportunistically reads a client-supplied confidence tag off a FINANCE_ENTRY payload;
     * the actual HIGH/MEDIUM/LOW scoring happens downstream in life-tracker. */
    private static String confidenceOf(SyncPayload payload) {
        Object raw = payload.getData().get("confidence");
        return raw == null ? "unknown" : raw.toString();
    }

    /**
     * AEAD additional-authenticated-data binds the ciphertext to the
     * envelope's routing metadata. Any tamper of routingId, senderDeviceId,
     * version, or nonce trips the Poly1305 tag at decrypt time.
     */
    static byte[] aad(SyncEnvelope env) {
        String s = env.getVersion() + "|" + env.getRoutingId() + "|"
                + env.getSenderDeviceId() + "|" + env.getNonceB64();
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void audit(AuditEventType type, SyncEnvelope env, Map<String, Object> extras) {
        AuditPublisher audit = auditProvider.getIfAvailable();
        if (audit == null) return;
        Map<String, Object> payload = new java.util.HashMap<>(extras);
        payload.put("routingId", env.getRoutingId());
        payload.put("senderDeviceId", env.getSenderDeviceId());
        audit.audit(type, null, null, env.getSenderDeviceId(), null, payload);
    }
}
