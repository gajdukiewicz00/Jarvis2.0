package org.jarvis.memory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targets the branches of {@link ChunkingService} that the happy-path
 * {@code ChunkingServiceTest} doesn't reach: the private word-boundary
 * splitter ({@code forceSplit}/{@code getOverlapText}), the post-loop
 * "last chunk" flush in {@code chunkText}, and the list overload of
 * {@code estimateTokens}.
 *
 * <p>{@code forceSplit} is reached through {@code chunkText} only when every
 * sentence-level chunk is dropped for being under {@code minSize} — reaching
 * its internal word-boundary-snap branch (both taken and not-taken) from that
 * entry point would require contradictory size relationships (see inline
 * notes), so those two lines are exercised directly via reflection instead,
 * with hand-verified inputs that provably terminate (each iteration is shown
 * to make forward progress) and a {@link Timeout} as a defense-in-depth
 * guard against any regression that would make it loop.</p>
 */
class ChunkingServiceForceSplitTest {

    @SuppressWarnings("unchecked")
    private static List<String> invokeForceSplit(ChunkingService service, String text) throws Exception {
        Method m = ChunkingService.class.getDeclaredMethod("forceSplit", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(service, text);
    }

    private static String invokeGetOverlapText(ChunkingService service, String chunk) throws Exception {
        Method m = ChunkingService.class.getDeclaredMethod("getOverlapText", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, chunk);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void forceSplitSnapsToWordBoundaryWhenFarEnoughAheadAndKeepsSlicesAboveMinSize() throws Exception {
        // chunkSize=10, minSize=5, overlap=2. minSize >= overlap guarantees that
        // whenever the word-boundary snap triggers (guarded by
        // spaceIdx > start + minSize) the new start (spaceIdx - overlap) is still
        // strictly greater than the old start; chunkSize > overlap guarantees the
        // same when it doesn't. Both keep the loop making forward progress.
        ChunkingService service = new ChunkingService(10, 2, 5);
        String text = "1234567 89ABCDEFGHIJ 0123456789";

        List<String> chunks = invokeForceSplit(service, text);

        assertThat(chunks).containsExactly("1234567", "67 89ABCDE", "DEFGHIJ", "IJ 0123456");
    }

    @Test
    void getOverlapTextReturnsChunkUnchangedWhenAtOrBelowOverlapLength() throws Exception {
        ChunkingService service = new ChunkingService(500, 50, 100);

        String result = invokeGetOverlapText(service, "short");

        assertThat(result).isEqualTo("short");
    }

    @Test
    void getOverlapTextReturnsFullTailWhenNoSpaceFoundNearBoundary() throws Exception {
        ChunkingService service = new ChunkingService(500, 50, 100);
        String chunk = "A".repeat(60);

        String result = invokeGetOverlapText(service, chunk);

        assertThat(result).hasSize(50).isEqualTo("A".repeat(50));
    }

    @Test
    void chunkTextFlushesFinalLeftoverChunkAfterLoopCompletes() {
        // Two sentences: the first alone stays under chunkSize (no mid-loop flush
        // yet); appending the second overflows chunkSize, flushing the first
        // sentence and starting a fresh chunk from the overlap tail + the whole
        // second sentence. With nothing left to process afterwards, that fresh
        // chunk is only ever flushed by the post-loop "last chunk" check.
        ChunkingService service = new ChunkingService(500, 50, 100);
        String sentence1 = "A".repeat(290) + ".";
        String sentence2 = "B".repeat(250) + ".";
        String text = sentence1 + " " + sentence2;

        List<String> chunks = service.chunkText(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(sentence1);
        assertThat(chunks.get(1)).endsWith(sentence2);
        assertThat(chunks.get(1).length()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void chunkTextFallsBackToForceSplitWhenEverySentenceChunkIsDroppedForBeingTooShort() {
        // chunkSize=5 forces a flush after essentially every short "Xx."
        // sentence; minSize=50 means each individual flush (a few characters)
        // is always discarded, so chunks stays empty even though the combined
        // text is 50+ characters — triggering the forceSplit fallback. Under
        // this same chunkSize/minSize pairing every forceSplit slice is also
        // capped at ~5 chars, so the fallback likewise finds nothing to keep.
        ChunkingService service = new ChunkingService(5, 2, 50);
        String text = "Ab. Cd. Ef. Gh. Ij. Kl. Mn. Op. Qr. St. Uv. Wx. Yz. Aa. Bb. Cc.";
        assertThat(text.length()).isGreaterThan(50);

        List<String> chunks = service.chunkText(text);

        assertThat(chunks).isEmpty();
    }

    @Test
    void estimateTokensForListSumsPerTextEstimates() {
        ChunkingService service = new ChunkingService(500, 50, 100);

        int total = service.estimateTokens(List.of("a".repeat(100), "b".repeat(40)));

        assertThat(total).isEqualTo(25 + 10);
    }
}
