package org.jarvis.syncservice;

import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.sync.PairingResponse;
import org.jarvis.sync.SyncEnvelope;
import org.jarvis.sync.SyncPayload;
import org.jarvis.sync.SyncPayloadKind;
import org.jarvis.sync.crypto.SyncCrypto;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.dispatch.DispatchClient;
import org.jarvis.syncservice.dispatch.DispatchClient.DispatchResult;
import org.jarvis.syncservice.repository.InMemoryPairingStore;
import org.jarvis.syncservice.repository.PairingStore;
import org.jarvis.syncservice.service.BlobInboxService;
import org.jarvis.syncservice.service.PairingNonceStore;
import org.jarvis.syncservice.service.PairingService;
import org.jarvis.syncservice.service.ReplayCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link BlobInboxService} paths not exercised by {@link BlobInboxServiceTest}:
 * the HEALTH_ENTRY dispatch arm, an UNKNOWN payload kind (rejected as UNSUPPORTED_KIND),
 * a plaintext that decrypts fine but fails JSON parsing (TAMPERED via payload_parse),
 * and the audit-emit body itself (only reachable when the {@link AuditPublisher}
 * {@code ObjectProvider} actually supplies a publisher, unlike the silent-audit setup
 * used everywhere else in this test module).
 */
class BlobInboxServiceAuditAndEdgeCasesTest {

    private SimulatedDevice device;
    private BlobInboxService inbox;
    private AuditPublisher auditPublisher;
    private RecordingDispatch dispatch;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        SyncCrypto crypto = new SyncCrypto();
        PairingStore pairings = new InMemoryPairingStore();

        @SuppressWarnings("unchecked")
        ObjectProvider<AuditPublisher> silentAudit = mock(ObjectProvider.class);
        when(silentAudit.getIfAvailable()).thenReturn(null);
        when(silentAudit.stream()).thenReturn(Stream.empty());

        PairingService pairingService = new PairingService(crypto,
                new PairingNonceStore(props), pairings, silentAudit);
        var init = pairingService.initPairing();
        device = new SimulatedDevice();
        var req = device.buildPairingRequest("Audit-Test-Device", init.pairingNonceB64(), init.serverKexPubB64());
        PairingResponse pairing = pairingService.completePairing(req);
        device.rememberPairingResponse(pairing.getRoutingId(), pairing.getSenderDeviceId());

        auditPublisher = mock(AuditPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditPublisher> liveAudit = mock(ObjectProvider.class);
        when(liveAudit.getIfAvailable()).thenReturn(auditPublisher);

        dispatch = new RecordingDispatch();
        inbox = new BlobInboxService(crypto, pairings, new ReplayCache(props), dispatch, liveAudit);
    }

    @Test
    void healthEntryReachesHealthDispatch() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.HEALTH_ENTRY, "n-health",
                Instant.now(), Map.of("sleepHours", 7.5, "steps", 5000));
        SyncEnvelope env = device.sealEnvelope(payload);

        var r = inbox.ingest(env);

        assertThat(r.ok()).isTrue();
        assertThat(dispatch.lastHealth.get()).isNotNull();
        assertThat(dispatch.lastHealth.get().getData()).containsEntry("steps", 5000);
    }

    @Test
    void unknownKindIsRejectedAsUnsupported() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.UNKNOWN, "n-unk-kind",
                Instant.now(), Map.of());
        SyncEnvelope env = device.sealEnvelope(payload);

        var r = inbox.ingest(env);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BlobInboxService.Status.UNSUPPORTED_KIND);
        assertThat(r.detail()).isEqualTo("unknown_kind");
    }

    @Test
    void malformedPlaintextAfterSuccessfulDecryptIsTampered() {
        SyncEnvelope env = sealRawBytes(device, "not-json-at-all".getBytes(StandardCharsets.UTF_8));

        var r = inbox.ingest(env);

        assertThat(r.status()).isEqualTo(BlobInboxService.Status.TAMPERED);
        assertThat(r.detail()).isEqualTo("payload JSON malformed");
    }

    @Test
    void acceptedBlobPublishesAuditEventWhenPublisherAvailable() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-audit",
                Instant.now(), Map.of("amount", 2.0));
        SyncEnvelope env = device.sealEnvelope(payload);

        var r = inbox.ingest(env);

        assertThat(r.ok()).isTrue();
        verify(auditPublisher).audit(eq(AuditEventType.SYNC_BLOB_RECEIVED),
                isNull(), isNull(), eq(env.getSenderDeviceId()), isNull(), anyMap());
    }

    @Test
    void replayRejectionPublishesAuditEventWhenPublisherAvailable() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-audit-replay",
                Instant.now(), Map.of("amount", 2.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        inbox.ingest(env);

        var second = inbox.ingest(env);

        assertThat(second.status()).isEqualTo(BlobInboxService.Status.REPLAY);
        verify(auditPublisher).audit(eq(AuditEventType.SYNC_BLOB_REPLAY_REJECTED),
                isNull(), isNull(), eq(env.getSenderDeviceId()), isNull(), anyMap());
    }

    /**
     * Seals arbitrary raw bytes (not a JSON-serialized {@link SyncPayload}) under the
     * device's real session key with a correctly-formed AAD, so the AEAD tag verifies
     * but the plaintext fails downstream JSON parsing. Mirrors the AAD construction
     * {@code BlobInboxService.aad(...)} uses internally (that method is package-private
     * to org.jarvis.syncservice.service, so it can't be called directly from here).
     */
    private static SyncEnvelope sealRawBytes(SimulatedDevice device, byte[] plaintext) {
        SyncCrypto crypto = device.crypto();
        byte[] nonce = crypto.randomNonce();
        String nonceB64 = SyncCrypto.b64(nonce);
        String routingId = device.routingId();
        String deviceId = device.deviceId();
        int version = SyncEnvelope.CURRENT_VERSION;
        byte[] aad = (version + "|" + routingId + "|" + deviceId + "|" + nonceB64)
                .getBytes(StandardCharsets.UTF_8);
        byte[] ct = crypto.seal(device.sessionKey(), nonce, aad, plaintext);
        return new SyncEnvelope(version, routingId, deviceId, nonceB64, SyncCrypto.b64(ct), Instant.now());
    }

    static class RecordingDispatch implements DispatchClient {
        final AtomicReference<SyncPayload> lastFinance = new AtomicReference<>();
        final AtomicReference<SyncPayload> lastCommand = new AtomicReference<>();
        final AtomicReference<SyncPayload> lastHealth = new AtomicReference<>();

        @Override public DispatchResult dispatchFinanceEntry(String userId, SyncPayload p) {
            lastFinance.set(p);
            return DispatchResult.success();
        }
        @Override public DispatchResult dispatchCommandIntent(String userId, SyncPayload p) {
            lastCommand.set(p);
            return DispatchResult.success();
        }
        @Override public DispatchResult dispatchHealthEntry(String userId, SyncPayload p) {
            lastHealth.set(p);
            return DispatchResult.success();
        }
    }
}
