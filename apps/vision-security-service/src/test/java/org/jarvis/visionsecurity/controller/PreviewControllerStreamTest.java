package org.jarvis.visionsecurity.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.service.CameraCaptureService;
import org.jarvis.visionsecurity.service.VisionPipelineService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link PreviewController#stream} and its private {@code streamFrames}
 * loop directly (no MockMvc), since the real endpoint returns a
 * {@link StreamingResponseBody} whose {@code writeTo} runs an infinite
 * {@code while (!Thread.currentThread().isInterrupted())} loop — invoking that
 * through Spring's async request-dispatch machinery in a MockMvc test would
 * either hang or require asserting on real request timing. Here we start the
 * loop on a background thread, capture one written MJPEG frame, then
 * interrupt it, which is deterministic and doesn't depend on servlet-container
 * async plumbing.
 */
class PreviewControllerStreamTest {

    private final CameraCaptureService cameraCaptureService = mock(CameraCaptureService.class);
    private final VisionPipelineService pipelineService = mock(VisionPipelineService.class);
    private PreviewController controller;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @BeforeEach
    void setUp() {
        controller = new PreviewController(cameraCaptureService, pipelineService);
    }

    @Test
    void streamReturnsForbiddenForNonLoopbackCaller() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("8.8.8.8");

        ResponseEntity<StreamingResponseBody> response = controller.stream("owner", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void streamReturnsMjpegContentTypeForLoopbackCaller() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        ResponseEntity<StreamingResponseBody> response = controller.stream("owner", request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("multipart/x-mixed-replace; boundary=frame"));
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void streamFramesWritesAtLeastOneMjpegFrameThenStopsOnInterrupt() throws Exception {
        Mat frame = new Mat(120, 160, CvType.CV_8UC3, new Scalar(40, 40, 40));
        when(cameraCaptureService.captureFrame("vision preview")).thenReturn(frame);
        PipelineResult result = new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised",
                List.of(new FaceMatch(new RectBox(10, 10, 40, 40), FaceVerdict.OWNER, 30.0)), null, null);
        when(pipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(result);

        Method streamFrames = PreviewController.class.getDeclaredMethod(
                "streamFrames", java.io.OutputStream.class, String.class);
        streamFrames.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Thread worker = new Thread(() -> {
            try {
                streamFrames.invoke(controller, out, "owner");
            } catch (Exception ignored) {
                // Expected once the thread below interrupts it mid-sleep.
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(3_000);

        assertThat(worker.isAlive()).isFalse();
        String written = out.toString(StandardCharsets.US_ASCII);
        assertThat(written).contains("--frame").contains("Content-Type: image/jpeg");
    }
}
