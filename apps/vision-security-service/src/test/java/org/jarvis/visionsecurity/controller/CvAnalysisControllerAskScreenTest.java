package org.jarvis.visionsecurity.controller;

import org.jarvis.visionsecurity.model.AskScreenResult;
import org.jarvis.visionsecurity.service.LocalCvService;
import org.jarvis.visionsecurity.service.cv.AskScreenCvService;
import org.jarvis.visionsecurity.service.cv.ScreenContextCvService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Validation + routing of the ask-screen request contract. */
class CvAnalysisControllerAskScreenTest {

    private final AskScreenCvService askService = mock(AskScreenCvService.class);
    private final CvAnalysisController controller = new CvAnalysisController(
            mock(LocalCvService.class), mock(ScreenContextCvService.class), askService);

    private AskScreenResult ok() {
        return new AskScreenResult("q", null, null,
                new AskScreenResult.VlmInfo("ollama", "llava", "NOT_CONFIGURED", 0L, null),
                Instant.now(), 1L, true, null);
    }

    @Test
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> controller.askScreen(null,
                new CvAnalysisController.AskScreenRequest("  ", true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question is required");
    }

    @Test
    void captureFreshDefaultsToFreshScreenshot() {
        when(askService.ask(any(), any(), any())).thenReturn(ok());

        controller.askScreen(null,
                new CvAnalysisController.AskScreenRequest("what?", null, null));

        ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
        org.mockito.Mockito.verify(askService).ask(eq("anonymous"), eq("what?"), target.capture());
        assertThat(target.getValue()).isNull(); // null target => fresh capture
    }

    @Test
    void explicitImagePathTakesPrecedence() {
        when(askService.ask(any(), any(), any())).thenReturn(ok());

        controller.askScreen(null,
                new CvAnalysisController.AskScreenRequest("what?", true, "/tmp/shot.png"));

        ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
        org.mockito.Mockito.verify(askService).ask(any(), any(), target.capture());
        assertThat(target.getValue()).isEqualTo(Path.of("/tmp/shot.png"));
    }

    @Test
    void captureFreshFalseWithoutImagePathIsRejected() {
        assertThatThrownBy(() -> controller.askScreen(null,
                new CvAnalysisController.AskScreenRequest("what?", false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("captureFreshScreenshot=false requires");
    }
}
