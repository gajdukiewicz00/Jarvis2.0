package org.jarvis.syncservice;

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

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PairingServiceTest {

    private PairingService service;
    private PairingStore store;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        store = new InMemoryPairingStore();
        @SuppressWarnings("unchecked")
        ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> audit = mock(ObjectProvider.class);
        when(audit.getIfAvailable()).thenReturn(null);
        when(audit.stream()).thenReturn(Stream.empty());
        service = new PairingService(new SyncCrypto(),
                new PairingNonceStore(props),
                store,
                audit);
    }

    @Test
    void successfulPairingPersistsDeviceAndReturnsRoutingId() {
        var init = service.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        PairingRequest req = device.buildPairingRequest("Pixel 9", init.pairingNonceB64(), init.serverKexPubB64());

        PairingResponse resp = service.completePairing(req);

        assertThat(resp.getRoutingId()).startsWith("rt-");
        assertThat(resp.getSenderDeviceId()).startsWith("dev-");
        assertThat(resp.getServerKexPubB64()).isEqualTo(init.serverKexPubB64());
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findByDeviceId(resp.getSenderDeviceId())).isPresent();
        assertThat(store.findByRoutingId(resp.getRoutingId())).isPresent();
    }

    @Test
    void replayedNonceCannotBeUsedTwice() {
        var init = service.initPairing();
        SimulatedDevice d1 = new SimulatedDevice();
        PairingRequest req1 = d1.buildPairingRequest("Phone-1", init.pairingNonceB64(), init.serverKexPubB64());
        service.completePairing(req1);

        // Second device tries to claim the SAME pairing nonce.
        SimulatedDevice d2 = new SimulatedDevice();
        PairingRequest req2 = d2.buildPairingRequest("Phone-2", init.pairingNonceB64(), init.serverKexPubB64());
        assertThrows(PairingService.PairingRejectedException.class,
                () -> service.completePairing(req2));
    }

    @Test
    void wrongSignatureRejected() {
        var init = service.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        PairingRequest req = device.buildPairingRequest("Phone", init.pairingNonceB64(), init.serverKexPubB64());
        // Tamper signature.
        byte[] bad = new byte[64];
        PairingRequest tampered = new PairingRequest(req.getDeviceLabel(),
                req.getIdentityPubB64(), req.getKexPubB64(),
                req.getPairingNonceB64(), SyncCrypto.b64(bad));
        assertThrows(PairingService.PairingRejectedException.class,
                () -> service.completePairing(tampered));
    }

    @Test
    void unknownNonceRejected() {
        SimulatedDevice device = new SimulatedDevice();
        var fakeServerKex = new SyncCrypto().generateKexKeyPair();
        String fakeServerPubB64 = SyncCrypto.b64(new SyncCrypto().encodeX25519Pub(fakeServerKex.getPublic()));
        PairingRequest req = device.buildPairingRequest("Phone", "AAAA", fakeServerPubB64);
        assertThrows(PairingService.PairingRejectedException.class,
                () -> service.completePairing(req));
    }
}
