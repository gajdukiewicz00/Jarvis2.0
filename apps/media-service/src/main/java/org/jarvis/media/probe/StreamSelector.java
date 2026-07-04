package org.jarvis.media.probe;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministically picks the main-voice audio track. Order of preference:
 * <ol>
 *   <li>an explicit manual override index (must match an audio stream);</li>
 *   <li>among non-commentary tracks, one matching the requested language;</li>
 *   <li>among non-commentary tracks, the container-default track;</li>
 *   <li>the lowest-index non-commentary track;</li>
 *   <li>if every track is commentary, the lowest-index track overall.</li>
 * </ol>
 * Ties always break on the lowest stream index, so the result is stable.
 */
@Component
public class StreamSelector {

    public Optional<Integer> selectMainAudio(List<MediaStream> streams, String preferredLanguage, Integer overrideIndex) {
        List<MediaStream> audio = streams.stream()
                .filter(MediaStream::isAudio)
                .sorted(Comparator.comparingInt(MediaStream::index))
                .toList();
        if (audio.isEmpty()) {
            return Optional.empty();
        }
        if (overrideIndex != null) {
            return audio.stream()
                    .filter(s -> s.index() == overrideIndex)
                    .findFirst()
                    .map(MediaStream::index);
        }

        List<MediaStream> nonCommentary = audio.stream().filter(s -> !s.isCommentary()).toList();
        List<MediaStream> pool = nonCommentary.isEmpty() ? audio : nonCommentary;

        if (preferredLanguage != null && !preferredLanguage.isBlank()) {
            Optional<MediaStream> byLang = pool.stream()
                    .filter(s -> preferredLanguage.equalsIgnoreCase(s.language()))
                    .findFirst();
            if (byLang.isPresent()) {
                return byLang.map(MediaStream::index);
            }
        }

        return pool.stream()
                .filter(MediaStream::isDefault)
                .findFirst()
                .or(() -> pool.stream().findFirst())
                .map(MediaStream::index);
    }
}
