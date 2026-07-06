package org.jarvis.syncservice.repository;

import org.jarvis.sync.PairingResponse;
import org.jarvis.sync.crypto.SyncCrypto;
import org.jarvis.syncservice.SimulatedDevice;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.PairedDevice;
import org.jarvis.syncservice.service.PairingNonceStore;
import org.jarvis.syncservice.service.PairingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Item #5 — a sync-service restart must not drop paired devices. Verifies
 * {@link FilePairingStore} actually persists to disk and reloads across a
 * brand-new instance (simulated restart), and that it degrades gracefully
 * (fail-soft) instead of crashing when the backing path is unusable.
 */
class FilePairingStoreTest {

    @TempDir
    Path tempDir;

    private ObjectProvider<org.jarvis.common.eventbus.AuditPublisher> silentAudit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        silentAudit = mock(ObjectProvider.class);
        when(silentAudit.getIfAvailable()).thenReturn(null);
        when(silentAudit.stream()).thenReturn(Stream.empty());
    }

    private SyncServiceProperties propsWithPath(Path storeFile) {
        SyncServiceProperties props = new SyncServiceProperties();
        props.setPairedDevicesPath(storeFile.toString());
        return props;
    }

    @Test
    void pairDevice_thenReloadStoreFromDisk_stillPaired() {
        Path storeFile = tempDir.resolve("paired-devices.json");
        SyncServiceProperties props = propsWithPath(storeFile);

        FilePairingStore beforeRestart = new FilePairingStore(props);
        PairingService pairingService = new PairingService(new SyncCrypto(),
                new PairingNonceStore(props), beforeRestart, silentAudit);

        var init = pairingService.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        var req = device.buildPairingRequest("Pixel 9", init.pairingNonceB64(), init.serverKexPubB64());
        PairingResponse resp = pairingService.completePairing(req);

        assertThat(beforeRestart.size()).isEqualTo(1);
        assertThat(Files.exists(storeFile)).isTrue();

        // Simulate a sync-service restart: a brand-new store instance pointed at the
        // same backing file, with nothing carried over in memory.
        FilePairingStore afterRestart = new FilePairingStore(props);

        assertThat(afterRestart.size()).isEqualTo(1);
        Optional<PairedDevice> reloaded = afterRestart.findByDeviceId(resp.getSenderDeviceId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().routingId()).isEqualTo(resp.getRoutingId());
        assertThat(reloaded.get().sessionKey().key()).isEqualTo(device.sessionKey().key());
        assertThat(afterRestart.findByRoutingId(resp.getRoutingId())).isPresent();
    }

    @Test
    void save_persistsSessionKeyMaterialAsBase64Json() throws IOException {
        Path storeFile = tempDir.resolve("paired-devices.json");
        SyncServiceProperties props = propsWithPath(storeFile);
        FilePairingStore store = new FilePairingStore(props);
        PairingService pairingService = new PairingService(new SyncCrypto(),
                new PairingNonceStore(props), store, silentAudit);

        var init = pairingService.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        var req = device.buildPairingRequest("Phone", init.pairingNonceB64(), init.serverKexPubB64());
        pairingService.completePairing(req);

        String json = Files.readString(storeFile, StandardCharsets.UTF_8);
        assertThat(json).contains("sessionKeyB64").contains("routingId").contains("deviceId");
    }

    @Test
    void constructor_corruptFile_startsEmptyInsteadOfThrowing() throws IOException {
        Path storeFile = tempDir.resolve("paired-devices.json");
        Files.writeString(storeFile, "{ not valid json [[[", StandardCharsets.UTF_8);
        SyncServiceProperties props = propsWithPath(storeFile);

        FilePairingStore store = new FilePairingStore(props);

        assertThat(store.size()).isZero();
    }

    @Test
    void save_unwritablePath_fallsBackToInMemoryInsteadOfThrowing() throws IOException {
        // Make the "parent directory" a regular file, so Files.createDirectories(parent)
        // must fail -- this simulates a missing/unmountable persistent volume.
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "blocked", StandardCharsets.UTF_8);
        Path storeFile = blockingFile.resolve("paired-devices.json");
        SyncServiceProperties props = propsWithPath(storeFile);

        FilePairingStore store = new FilePairingStore(props);
        PairingService pairingService = new PairingService(new SyncCrypto(),
                new PairingNonceStore(props), store, silentAudit);

        var init = pairingService.initPairing();
        SimulatedDevice device = new SimulatedDevice();
        var req = device.buildPairingRequest("Phone", init.pairingNonceB64(), init.serverKexPubB64());

        // Must not throw even though persistence is impossible.
        PairingResponse resp = pairingService.completePairing(req);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findByDeviceId(resp.getSenderDeviceId())).isPresent();
    }
}
