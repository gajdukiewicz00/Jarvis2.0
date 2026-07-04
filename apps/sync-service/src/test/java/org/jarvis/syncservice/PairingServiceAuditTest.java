package org.jarvis.syncservice;

import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.sync.PairingRequest;
import org.jarvis.sync.PairingResponse;
import org.jarvis.sync.crypto.SyncCrypto;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.repository.InMemoryPairingStore;
import org.jarvis.syncservice.repository.PairingStore;
import org.jarvis.syncservice.service.PairingNonceStore;
import org.jarvis.syncservice.service.PairingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the audit-emit branches of {@link PairingService} that {@link PairingServiceTest}
 * intentionally leaves untested (that suite wires a silent {@code ObjectProvider} that
 * always returns {@code null}, so {@code audit != null} is never true there).
 */
class PairingServiceAuditTest {

    private PairingService service;
    private AuditPublisher auditPublisher;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        PairingStore store = new InMemoryPairingStore();
        auditPublisher = mock(AuditPublisher.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<AuditPublisher> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(auditPublisher);

        service = new PairingService(new SyncCrypto(), new PairingNonceStore(props), store, audit);
    }

    @Test
    void successfulPairingPublishesDevicePairedAuditEvent() {
        var init = service.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        PairingRequest req = device.buildPairingRequest("Audit Phone", init.pairingNonceB64(), init.serverKexPubB64());

        PairingResponse resp = service.completePairing(req);

        assertThat(resp.getRoutingId()).startsWith("rt-");
        verify(auditPublisher).audit(eq(AuditEventType.SYNC_DEVICE_PAIRED),
                isNull(), isNull(), eq(resp.getSenderDeviceId()), isNull(), anyMap());
    }

    @Test
    void rejectedPairingPublishesPairingRejectedAuditEvent() {
        SimulatedDevice device = new SimulatedDevice();
        var fakeServerKex = new SyncCrypto().generateKexKeyPair();
        String fakeServerPubB64 = SyncCrypto.b64(new SyncCrypto().encodeX25519Pub(fakeServerKex.getPublic()));
        PairingRequest req = device.buildPairingRequest("Audit Phone 2", "unknown-nonce", fakeServerPubB64);

        assertThrows(PairingService.PairingRejectedException.class,
                () -> service.completePairing(req));

        verify(auditPublisher).audit(eq(AuditEventType.SYNC_DEVICE_PAIRING_REJECTED),
                isNull(), isNull(), eq(req.getIdentityPubB64()), isNull(), anyMap());
    }
}
