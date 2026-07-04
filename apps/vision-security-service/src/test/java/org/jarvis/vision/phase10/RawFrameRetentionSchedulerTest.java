package org.jarvis.vision.phase10;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RawFrameRetentionSchedulerTest {

    @TempDir
    Path tempDir;

    private VisionRetentionProperties properties;
    private VisionEventEmitter emitter;
    private RawFrameRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new VisionRetentionProperties();
        properties.setEnabled(true);
        properties.setDays(7);
        properties.setRoot(tempDir.toString());
        emitter = mock(VisionEventEmitter.class);
        scheduler = new RawFrameRetentionScheduler(properties, emitter);
    }

    private Path writeFrame(String relative, Instant lastModified) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "raw frame bytes simulated");
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }

    @Test
    void freshFilesAreKept() throws Exception {
        writeFrame("owner/incidents/2026-05-01/frame-1.bin", Instant.now().minus(Duration.ofDays(1)));
        var result = scheduler.sweepInternal(tempDir,
                Instant.now().minus(Duration.ofDays(7)));
        assertThat(result.filesDeleted).isZero();
    }

    @Test
    void oldFilesAreDeletedAndCounted() throws Exception {
        Path keep = writeFrame("owner/incidents/recent/frame-keep.bin",
                Instant.now().minus(Duration.ofDays(2)));
        Path purge1 = writeFrame("owner/incidents/old/frame-1.bin",
                Instant.now().minus(Duration.ofDays(30)));
        Path purge2 = writeFrame("owner/incidents/old/frame-2.bin",
                Instant.now().minus(Duration.ofDays(45)));

        var result = scheduler.sweepInternal(tempDir,
                Instant.now().minus(Duration.ofDays(7)));

        assertThat(Files.exists(keep)).isTrue();
        assertThat(Files.exists(purge1)).isFalse();
        assertThat(Files.exists(purge2)).isFalse();
        assertThat(result.filesDeleted).isEqualTo(2);
        assertThat(result.bytesFreed).isPositive();
    }

    @Test
    void emptyDirectoriesAreCleanedUp() throws Exception {
        Path inner = writeFrame("owner/incidents/empty-after/frame.bin",
                Instant.now().minus(Duration.ofDays(20)));
        scheduler.sweepInternal(tempDir, Instant.now().minus(Duration.ofDays(7)));
        assertThat(Files.exists(inner.getParent())).isFalse();
    }

    @Test
    void disabledShortCircuits() {
        properties.setEnabled(false);
        scheduler.sweep();
        verify(emitter, times(0)).framesPurged(0, 0L, 7);
    }

    @Test
    void missingRootIsHandled() {
        properties.setRoot(tempDir.resolve("nope").toString());
        scheduler.sweep();
        // emitter not called — sweep exited cleanly
        verify(emitter, times(0)).framesPurged(0, 0L, 7);
    }

    @Test
    void scheduledRunEmitsOneSummary() throws Exception {
        writeFrame("owner/incidents/old/frame.bin", Instant.now().minus(Duration.ofDays(30)));
        scheduler.sweep();
        verify(emitter, times(1)).framesPurged(1, "raw frame bytes simulated".length(), 7);
    }
}
