package org.jarvis.media.probe;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Inspects a media file and returns its structured streams plus the chosen main
 * audio track. Probing is metadata-only and fast, so it runs synchronously (with a
 * hard ffprobe timeout) rather than as an async job.
 */
@Slf4j
@Service
public class ProbeService {

    private final WorkspaceManager workspace;
    private final FFprobeClient ffprobe;
    private final FFprobeParser parser;
    private final StreamSelector selector;

    public ProbeService(WorkspaceManager workspace, FFprobeClient ffprobe,
                        FFprobeParser parser, StreamSelector selector) {
        this.workspace = workspace;
        this.ffprobe = ffprobe;
        this.parser = parser;
        this.selector = selector;
    }

    public ProbeResult probe(ProbeRequest request) {
        Path file = workspace.validateInputPath(request.inputFile());
        String json = ffprobe.probeJson(file);
        List<MediaStream> streams = parser.parse(json);
        Optional<Integer> mainAudio = selector.selectMainAudio(
                streams, request.preferredLanguage(), request.overrideAudioIndex());
        log.debug("Probed {} -> {} streams, main audio index={}",
                file.getFileName(), streams.size(), mainAudio.orElse(null));
        return ProbeResult.from(streams, mainAudio.orElse(null));
    }
}
