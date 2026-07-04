package org.jarvis.media.probe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSelectorTest {

    private final StreamSelector selector = new StreamSelector();

    private MediaStream audio(int index, String lang, boolean isDefault, boolean commentary) {
        return new MediaStream(index, "audio", "aac", lang, 2, 1800.0, isDefault, commentary, null);
    }

    @Test
    void selectsDefaultNonCommentaryTrack() {
        List<MediaStream> streams = List.of(
                audio(1, "eng", true, false),
                audio(2, "eng", false, true));
        assertThat(selector.selectMainAudio(streams, null, null)).contains(1);
    }

    @Test
    void prefersRequestedLanguage() {
        List<MediaStream> streams = List.of(
                audio(1, "eng", true, false),
                audio(2, "rus", false, false));
        assertThat(selector.selectMainAudio(streams, "rus", null)).contains(2);
    }

    @Test
    void avoidsCommentaryWhenSelecting() {
        List<MediaStream> streams = List.of(
                audio(1, "eng", false, true),   // commentary, even though first
                audio(2, "eng", true, false));
        assertThat(selector.selectMainAudio(streams, null, null)).contains(2);
    }

    @Test
    void manualOverrideWins() {
        List<MediaStream> streams = List.of(
                audio(1, "eng", true, false),
                audio(2, "eng", false, true));
        assertThat(selector.selectMainAudio(streams, null, 2)).contains(2);
    }

    @Test
    void overrideThatIsNotAudioYieldsEmpty() {
        List<MediaStream> streams = List.of(audio(1, "eng", true, false));
        assertThat(selector.selectMainAudio(streams, null, 9)).isEmpty();
    }

    @Test
    void noAudioYieldsEmpty() {
        List<MediaStream> streams = List.of(
                new MediaStream(0, "video", "h264", null, null, 1800.0, true, false, null));
        assertThat(selector.selectMainAudio(streams, null, null)).isEmpty();
    }

    @Test
    void allCommentaryFallsBackToLowestIndex() {
        List<MediaStream> streams = List.of(
                audio(3, "eng", false, true),
                audio(1, "eng", false, true));
        Optional<Integer> selected = selector.selectMainAudio(streams, null, null);
        assertThat(selected).contains(1);
    }
}
