package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.FileBackedMediaJobStore;
import org.jarvis.media.job.InMemoryMediaJobStore;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct (non-MockMvc) coverage of {@link MediaStatusController}: proves the status
 * view reports {@code mock} for every provider by default (per {@code
 * MediaTestFactory.props}, which mirrors {@code application.yml}'s defaults) and
 * correctly identifies whichever {@link MediaJobStore} implementation is wired,
 * without requiring the feature flag to be enabled.
 */
class MediaStatusControllerTest {

    @TempDir
    Path tmp;

    @Test
    void reportsMockForEveryProviderByDefault() {
        MediaProperties props = MediaTestFactory.props(tmp);
        MediaStatusController controller = new MediaStatusController(props, MediaTestFactory.store());

        MediaStatusView status = controller.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.providers().ffprobe()).isEqualTo("mock");
        assertThat(status.providers().ffmpeg()).isEqualTo("mock");
        assertThat(status.providers().asr()).isEqualTo("mock");
        assertThat(status.providers().translation()).isEqualTo("mock");
        assertThat(status.providers().tts()).isEqualTo("mock");
    }

    @Test
    void reportsInMemoryJobStore() {
        MediaProperties props = MediaTestFactory.props(tmp);
        MediaStatusController controller = new MediaStatusController(props, new InMemoryMediaJobStore());

        assertThat(controller.status().jobStore()).isEqualTo("memory");
    }

    @Test
    void reportsFileJobStore() {
        MediaProperties props = MediaTestFactory.props(tmp);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MediaStatusController controller =
                new MediaStatusController(props, new FileBackedMediaJobStore(mapper, tmp.toString()));

        assertThat(controller.status().jobStore()).isEqualTo("file");
    }

    @Test
    void reportsEnabledFalseWithoutThrowing_soAUiCanExplainWhyJobEndpointsAreUnavailable() {
        MediaProperties disabled = new MediaProperties(
                false,
                new MediaProperties.Workspace(tmp.toString(), "", 24),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("mock", "ffmpeg", 600),
                new MediaProperties.Asr("mock", "whisper-cli", "", 120),
                new MediaProperties.Translation("mock", "http://llm-service:8091"),
                new MediaProperties.Tts("mock", false, "piper", "", 60),
                new MediaProperties.Subtitle(7, 0.5));
        MediaStatusController controller = new MediaStatusController(disabled, MediaTestFactory.store());

        assertThat(controller.status().enabled()).isFalse();
    }
}
