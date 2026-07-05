package org.jarvis.media.workspace;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.support.MediaTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceCleanupServiceTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void removesDirectoriesOlderThanTtlAndKeepsFreshOnes() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties props = MediaTestFactory.props(tmp, 24, false, 7, 0.5);

        Path stale = ws.resolveInWorkspace("stale-job/output.wav");
        Files.writeString(stale, "old artifact");
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofHours(48))));

        Path fresh = ws.resolveInWorkspace("fresh-job/output.wav");
        Files.writeString(fresh, "recent artifact");
        Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(Duration.ofHours(1))));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, props, fixedClock());
        int removed = cleanup.cleanupExpiredArtifacts();

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(ws.workspaceDir().resolve("stale-job"))).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void doesNotDeleteAnythingWhenAllDirectoriesAreWithinTtl() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties props = MediaTestFactory.props(tmp, 24, false, 7, 0.5);

        Path recent = ws.resolveInWorkspace("job/output.wav");
        Files.writeString(recent, "data");
        Files.setLastModifiedTime(recent, FileTime.from(NOW.minus(Duration.ofMinutes(5))));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, props, fixedClock());

        assertThat(cleanup.cleanupExpiredArtifacts()).isZero();
        assertThat(Files.exists(recent)).isTrue();
    }

    @Test
    void recursivelyDeletesEveryFileUnderAnExpiredJobDirectory() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties props = MediaTestFactory.props(tmp, 24, false, 7, 0.5);

        Path seg0 = ws.resolveInWorkspace("job/seg-0000.wav");
        Path seg1 = ws.resolveInWorkspace("job/seg-0001.wav");
        Files.writeString(seg0, "a");
        Files.writeString(seg1, "b");
        Instant old = NOW.minus(Duration.ofHours(72));
        Files.setLastModifiedTime(seg0, FileTime.from(old));
        Files.setLastModifiedTime(seg1, FileTime.from(old));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, props, fixedClock());
        int removed = cleanup.cleanupExpiredArtifacts();

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(ws.workspaceDir().resolve("job"))).isFalse();
    }

    @Test
    void zeroTtlExpiresEverythingImmediately() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties props = MediaTestFactory.props(tmp, 0, false, 7, 0.5);

        Path justWritten = ws.resolveInWorkspace("job/output.wav");
        Files.writeString(justWritten, "data");
        Files.setLastModifiedTime(justWritten, FileTime.from(NOW.minusSeconds(1)));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, props, fixedClock());

        assertThat(cleanup.cleanupExpiredArtifacts()).isEqualTo(1);
    }

    @Test
    void scheduledCleanupIsANoOpWhenMediaIsDisabled() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties enabledProps = MediaTestFactory.props(tmp, 24, false, 7, 0.5);
        MediaProperties disabledProps = new MediaProperties(
                false,
                enabledProps.workspace(), enabledProps.executor(), enabledProps.ffprobe(), enabledProps.ffmpeg(),
                enabledProps.asr(), enabledProps.translation(), enabledProps.tts(), enabledProps.subtitle());

        Path stale = ws.resolveInWorkspace("stale-job/output.wav");
        Files.writeString(stale, "old artifact");
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofHours(48))));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, disabledProps, fixedClock());
        cleanup.scheduledCleanup();

        assertThat(Files.exists(stale)).isTrue(); // never scanned because media.enabled=false
    }

    @Test
    void ignoresPlainFilesDirectlyUnderTheWorkspaceRoot() throws IOException {
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        MediaProperties props = MediaTestFactory.props(tmp, 24, false, 7, 0.5);

        Path rootFile = ws.workspaceDir().resolve("stray.txt");
        Files.writeString(rootFile, "not a job directory");
        Files.setLastModifiedTime(rootFile, FileTime.from(NOW.minus(Duration.ofHours(100))));

        WorkspaceCleanupService cleanup = new WorkspaceCleanupService(ws, props, fixedClock());

        assertThat(cleanup.cleanupExpiredArtifacts()).isZero();
        assertThat(Files.exists(rootFile)).isTrue();
    }
}
