package org.jarvis.syncservice.service;

import org.jarvis.syncservice.config.SyncServiceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item #6 — the replay/nonce cache used to be Caffeine in-memory only (24h TTL),
 * so a sync-service restart reset the replay window and an attacker's captured
 * blob could be replayed successfully. Verifies the persisted cache rejects
 * replays across a restart, still honors the 24h TTL, and cleans up expired
 * nonces instead of growing the backing file forever.
 */
class ReplayCacheTest {

    @TempDir
    Path tempDir;

    private SyncServiceProperties propsWithPath(Path storeFile) {
        SyncServiceProperties props = new SyncServiceProperties();
        props.setReplayCacheStorePath(storeFile.toString());
        return props;
    }

    @Test
    void recordIfUnseen_firstSightingTrue_secondSightingFalse() {
        ReplayCache cache = new ReplayCache(propsWithPath(tempDir.resolve("replay-cache.json")));

        assertThat(cache.recordIfUnseen("dev-1", "nonce-A")).isTrue();
        assertThat(cache.recordIfUnseen("dev-1", "nonce-A")).isFalse();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void differentDevices_sameNonce_trackedIndependently() {
        ReplayCache cache = new ReplayCache(propsWithPath(tempDir.resolve("replay-cache.json")));

        assertThat(cache.recordIfUnseen("dev-1", "nonce-shared")).isTrue();
        assertThat(cache.recordIfUnseen("dev-2", "nonce-shared")).isTrue();
    }

    @Test
    void replayRejected_afterSimulatedRestart() {
        Path storeFile = tempDir.resolve("replay-cache.json");
        SyncServiceProperties props = propsWithPath(storeFile);

        ReplayCache beforeRestart = new ReplayCache(props);
        assertThat(beforeRestart.recordIfUnseen("dev-1", "nonce-B")).isTrue();
        assertThat(Files.exists(storeFile)).isTrue();

        // Simulate a sync-service restart: a brand-new instance pointed at the same
        // backing file, with nothing carried over in memory.
        ReplayCache afterRestart = new ReplayCache(props);

        assertThat(afterRestart.recordIfUnseen("dev-1", "nonce-B")).isFalse();
    }

    @Test
    void expiredNonce_isPruned_andCanBeRecordedAgainAfterTtl() {
        Path storeFile = tempDir.resolve("replay-cache.json");
        SyncServiceProperties props = propsWithPath(storeFile);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReplayCache cache = new ReplayCache(props, clock);

        assertThat(cache.recordIfUnseen("dev-1", "nonce-C")).isTrue();
        assertThat(cache.recordIfUnseen("dev-1", "nonce-C")).isFalse();

        clock.advance(Duration.ofHours(24).plusMinutes(1));

        assertThat(cache.recordIfUnseen("dev-1", "nonce-C")).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void expiredNonce_isNotReloadedFromDisk_afterRestartPastTtl() {
        Path storeFile = tempDir.resolve("replay-cache.json");
        SyncServiceProperties props = propsWithPath(storeFile);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        ReplayCache beforeRestart = new ReplayCache(props, new MutableClock(t0));
        assertThat(beforeRestart.recordIfUnseen("dev-1", "nonce-D")).isTrue();

        // "Restart" after the TTL has fully elapsed: the persisted nonce must be
        // cleaned up on load, not treated as still-seen.
        ReplayCache afterRestart = new ReplayCache(props, new MutableClock(t0.plus(Duration.ofHours(25))));

        assertThat(afterRestart.size()).isZero();
        assertThat(afterRestart.recordIfUnseen("dev-1", "nonce-D")).isTrue();
    }

    @Test
    void constructor_corruptFile_startsEmptyInsteadOfThrowing() throws IOException {
        Path storeFile = tempDir.resolve("replay-cache.json");
        Files.writeString(storeFile, "not valid json {{{", StandardCharsets.UTF_8);
        SyncServiceProperties props = propsWithPath(storeFile);

        ReplayCache cache = new ReplayCache(props);

        assertThat(cache.size()).isZero();
        assertThat(cache.recordIfUnseen("dev-1", "nonce-E")).isTrue();
    }

    @Test
    void recordIfUnseen_unwritablePath_fallsBackToInMemoryInsteadOfThrowing() throws IOException {
        // Make the "parent directory" a regular file, so Files.createDirectories(parent)
        // must fail -- this simulates a missing/unmountable persistent volume.
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "blocked", StandardCharsets.UTF_8);
        Path storeFile = blockingFile.resolve("replay-cache.json");
        SyncServiceProperties props = propsWithPath(storeFile);

        ReplayCache cache = new ReplayCache(props);

        assertThat(cache.recordIfUnseen("dev-1", "nonce-F")).isTrue();
        assertThat(cache.recordIfUnseen("dev-1", "nonce-F")).isFalse();
    }

    /** Deterministic, advanceable {@link Clock} so TTL/expiry tests don't sleep 24 real hours. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
