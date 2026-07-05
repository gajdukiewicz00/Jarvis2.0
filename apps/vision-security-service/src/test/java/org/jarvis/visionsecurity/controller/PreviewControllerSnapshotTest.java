package org.jarvis.visionsecurity.controller;

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
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link PreviewController}'s {@code /snapshot.jpg}
 * endpoint: the loopback guard, a successful annotated-frame capture, and the
 * "camera/pipeline unavailable" fallback. The {@code /stream} endpoint's
 * background MJPEG loop is covered separately (without MockMvc/async
 * dispatch) in {@link PreviewControllerStreamTest} to avoid racing Spring's
 * async request handling.
 */
@WebMvcTest(controllers = PreviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class PreviewControllerSnapshotTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CameraCaptureService cameraCaptureService;

    @MockBean
    private VisionPipelineService pipelineService;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @Test
    void snapshotReturnsForbiddenForNonLoopbackCaller() throws Exception {
        mockMvc.perform(get("/api/v1/vision-security/preview/snapshot.jpg")
                        .with(request -> {
                            request.setRemoteAddr("8.8.8.8");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void snapshotReturnsAnnotatedJpegForLoopbackCaller() throws Exception {
        Mat frame = new Mat(120, 160, CvType.CV_8UC3, new Scalar(40, 40, 40));
        when(cameraCaptureService.captureFrame("vision preview")).thenReturn(frame);
        PipelineResult result = new PipelineResult(
                DecisionType.OWNER_PRESENT,
                1,
                "Owner recognised",
                List.of(new FaceMatch(new RectBox(10, 10, 40, 40), FaceVerdict.OWNER, 30.0)),
                null,
                null);
        when(pipelineService.analyze(eq(""), any(Mat.class), isNull())).thenReturn(result);

        mockMvc.perform(get("/api/v1/vision-security/preview/snapshot.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    void snapshotReturnsServiceUnavailableWhenCameraCaptureFails() throws Exception {
        when(cameraCaptureService.captureFrame("vision preview"))
                .thenThrow(new IllegalStateException("camera busy"));

        mockMvc.perform(get("/api/v1/vision-security/preview/snapshot.jpg"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void snapshotHonorsUserQueryParameterAndAnnotatesUnknownAndUncertainVerdicts() throws Exception {
        Mat frame = new Mat(120, 160, CvType.CV_8UC3, new Scalar(40, 40, 40));
        when(cameraCaptureService.captureFrame("vision preview")).thenReturn(frame);
        PipelineResult result = new PipelineResult(
                DecisionType.UNKNOWN_PERSON,
                2,
                "Unknown person confirmed",
                List.of(
                        new FaceMatch(new RectBox(10, 10, 40, 40), FaceVerdict.UNKNOWN, 120.0),
                        new FaceMatch(new RectBox(60, 10, 40, 40), FaceVerdict.UNCERTAIN, 80.0)),
                null,
                null);
        when(pipelineService.analyze(eq("owner"), any(Mat.class), isNull())).thenReturn(result);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/vision-security/preview/snapshot.jpg")
                        .param("user", "owner"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }
}
