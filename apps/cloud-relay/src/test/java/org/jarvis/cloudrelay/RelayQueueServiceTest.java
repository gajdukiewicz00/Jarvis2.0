package org.jarvis.cloudrelay;

import org.jarvis.cloudrelay.domain.OpaqueBlob.Direction;
import org.jarvis.cloudrelay.service.RelayQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelayQueueServiceTest {

    private RelayProperties props;
    private RelayQueueService svc;

    @BeforeEach
    void setUp() {
        props = new RelayProperties();
        props.setQueueCap(4);
        props.setMaxBlobBytes(1024);
        svc = new RelayQueueService(props);
    }

    @Test
    void upstreamAndDownstreamAreIsolatedQueues() {
        svc.enqueue("rt-1", Direction.TO_HOME, bytes(8));
        svc.enqueue("rt-1", Direction.TO_DEVICE, bytes(8));
        assertThat(svc.queueSize("rt-1", Direction.TO_HOME)).isEqualTo(1);
        assertThat(svc.queueSize("rt-1", Direction.TO_DEVICE)).isEqualTo(1);
    }

    @Test
    void differentRoutingIdsAreSeparate() {
        svc.enqueue("rt-1", Direction.TO_HOME, bytes(8));
        svc.enqueue("rt-2", Direction.TO_HOME, bytes(8));
        assertThat(svc.queueSize("rt-1", Direction.TO_HOME)).isEqualTo(1);
        assertThat(svc.queueSize("rt-2", Direction.TO_HOME)).isEqualTo(1);
    }

    @Test
    void capEvictsOldestOnOverflow() {
        for (int i = 0; i < 6; i++) svc.enqueue("rt-1", Direction.TO_HOME, new byte[]{(byte) i});
        assertThat(svc.queueSize("rt-1", Direction.TO_HOME)).isEqualTo(4);
        var blobs = svc.drain("rt-1", Direction.TO_HOME, 10);
        // Blobs 0 and 1 evicted (cap=4), 2..5 remain in FIFO order.
        assertThat(blobs).hasSize(4);
        assertThat(blobs.get(0).payload()[0]).isEqualTo((byte) 2);
        assertThat(blobs.get(3).payload()[0]).isEqualTo((byte) 5);
    }

    @Test
    void blobLargerThanCapRejected() {
        assertThrows(RelayQueueService.BlobTooLargeException.class,
                () -> svc.enqueue("rt-1", Direction.TO_HOME, new byte[2048]));
    }

    @Test
    void drainRespectsLimit() {
        for (int i = 0; i < 4; i++) svc.enqueue("rt-1", Direction.TO_HOME, new byte[]{(byte) i});
        var first = svc.drain("rt-1", Direction.TO_HOME, 2);
        assertThat(first).hasSize(2);
        assertThat(svc.queueSize("rt-1", Direction.TO_HOME)).isEqualTo(2);
        var second = svc.drain("rt-1", Direction.TO_HOME, 10);
        assertThat(second).hasSize(2);
        assertThat(svc.queueSize("rt-1", Direction.TO_HOME)).isZero();
    }

    @Test
    void payloadStoredVerbatim() {
        byte[] arbitrary = new byte[]{0x00, (byte) 0xff, 0x42, (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
        svc.enqueue("rt-1", Direction.TO_HOME, arbitrary);
        var first = svc.peekFirst("rt-1", Direction.TO_HOME).orElseThrow();
        assertThat(first.payload()).isEqualTo(arbitrary);
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }
}
