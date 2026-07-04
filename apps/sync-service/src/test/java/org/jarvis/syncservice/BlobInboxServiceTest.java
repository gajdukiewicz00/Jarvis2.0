package org.jarvis.syncservice;

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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlobInboxServiceTest {

    private SimulatedDevice device;
    private PairingStore pairings;
    private BlobInboxService inbox;
    private RecordingDispatch dispatch;
    private PairingResponse pairing;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        SyncCrypto crypto = new SyncCrypto();
        pairings = new InMemoryPairingStore();
        @SuppressWarnings("unchecked")
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(null);
        when(audit.stream()).thenReturn(Stream.empty());

        PairingService pairingService = new PairingService(crypto,
                new PairingNonceStore(props), pairings, audit);
        var init = pairingService.initPairing();
        device = new SimulatedDevice();
        var req = device.buildPairingRequest("Pixel-Test", init.pairingNonceB64(), init.serverKexPubB64());
        pairing = pairingService.completePairing(req);
        device.rememberPairingResponse(pairing.getRoutingId(), pairing.getSenderDeviceId());

        dispatch = new RecordingDispatch();
        inbox = new BlobInboxService(crypto, pairings, new ReplayCache(props), dispatch, audit);
    }

    @Test
    void financeEntryReachesLifeTrackerDispatch() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-001",
                Instant.parse("2026-05-01T08:30:00Z"),
                Map.of("amount", 12.5, "currency", "EUR", "category", "coffee", "type", "EXPENSE"));
        SyncEnvelope env = device.sealEnvelope(payload);

        var r = inbox.ingest(env);

        assertThat(r.ok()).isTrue();
        assertThat(dispatch.lastFinance.get()).isNotNull();
        assertThat(dispatch.lastFinance.get().getKind()).isEqualTo(SyncPayloadKind.FINANCE_ENTRY);
        assertThat(dispatch.lastFinance.get().getData()).containsEntry("currency", "EUR");
    }

    @Test
    void commandIntentReachesOrchestratorDispatch() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.COMMAND_INTENT, "n-002",
                Instant.now(),
                Map.of("text", "включи свет в комнате", "language", "ru"));
        SyncEnvelope env = device.sealEnvelope(payload);
        assertThat(inbox.ingest(env).ok()).isTrue();
        assertThat(dispatch.lastCommand.get()).isNotNull();
        assertThat(dispatch.lastCommand.get().getData()).containsEntry("text", "включи свет в комнате");
    }

    @Test
    void replayedBlobIsRejected() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-replay",
                Instant.now(), Map.of("amount", 1.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        assertThat(inbox.ingest(env).ok()).isTrue();
        var second = inbox.ingest(env);
        assertThat(second.ok()).isFalse();
        assertThat(second.status()).isEqualTo(BlobInboxService.Status.REPLAY);
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-tamper",
                Instant.now(), Map.of("amount", 1.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        byte[] ct = SyncCrypto.unb64(env.getCiphertextB64());
        ct[0] ^= 0x01;
        SyncEnvelope tampered = new SyncEnvelope(env.getVersion(), env.getRoutingId(),
                env.getSenderDeviceId(), env.getNonceB64(), SyncCrypto.b64(ct),
                env.getOccurredAtClient());
        var r = inbox.ingest(tampered);
        assertThat(r.status()).isEqualTo(BlobInboxService.Status.TAMPERED);
    }

    @Test
    void routingIdMismatchIsRejected() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-route",
                Instant.now(), Map.of("amount", 1.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        SyncEnvelope wrong = new SyncEnvelope(env.getVersion(), "rt-wrong",
                env.getSenderDeviceId(), env.getNonceB64(), env.getCiphertextB64(),
                env.getOccurredAtClient());
        var r = inbox.ingest(wrong);
        assertThat(r.status()).isEqualTo(BlobInboxService.Status.ROUTING_MISMATCH);
    }

    @Test
    void unknownDeviceIsRejected() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-unk",
                Instant.now(), Map.of("amount", 1.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        SyncEnvelope wrong = new SyncEnvelope(env.getVersion(), env.getRoutingId(),
                "dev-nobody", env.getNonceB64(), env.getCiphertextB64(),
                env.getOccurredAtClient());
        var r = inbox.ingest(wrong);
        assertThat(r.status()).isEqualTo(BlobInboxService.Status.UNKNOWN_DEVICE);
    }

    @Test
    void dispatchFailureSurfacesAsBadGateway() throws Exception {
        dispatch.financeFailure = "life-tracker down";
        SyncPayload payload = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n-disp",
                Instant.now(), Map.of("amount", 1.0));
        SyncEnvelope env = device.sealEnvelope(payload);
        var r = inbox.ingest(env);
        assertThat(r.status()).isEqualTo(BlobInboxService.Status.DISPATCH_FAILED);
        assertThat(r.detail()).contains("life-tracker down");
    }

    @Test
    void heartbeatTouchesLastSeenWithoutDispatch() throws Exception {
        SyncPayload payload = new SyncPayload(SyncPayloadKind.DEVICE_HEARTBEAT, "n-hb",
                Instant.now(), Map.of());
        SyncEnvelope env = device.sealEnvelope(payload);
        var r = inbox.ingest(env);
        assertThat(r.ok()).isTrue();
        assertThat(dispatch.lastFinance.get()).isNull();
        assertThat(dispatch.lastCommand.get()).isNull();
    }

    static class RecordingDispatch implements DispatchClient {
        final AtomicReference<SyncPayload> lastFinance = new AtomicReference<>();
        final AtomicReference<SyncPayload> lastCommand = new AtomicReference<>();
        String financeFailure;
        String commandFailure;

        @Override public DispatchResult dispatchFinanceEntry(String userId, SyncPayload p) {
            if (financeFailure != null) return DispatchResult.failure(financeFailure);
            lastFinance.set(p);
            return DispatchResult.success();
        }
        @Override public DispatchResult dispatchCommandIntent(String userId, SyncPayload p) {
            if (commandFailure != null) return DispatchResult.failure(commandFailure);
            lastCommand.set(p);
            return DispatchResult.success();
        }

        final AtomicReference<SyncPayload> lastHealth = new AtomicReference<>();
        String healthFailure;

        @Override public DispatchResult dispatchHealthEntry(String userId, SyncPayload p) {
            if (healthFailure != null) return DispatchResult.failure(healthFailure);
            lastHealth.set(p);
            return DispatchResult.success();
        }
    }
}
