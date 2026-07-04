package org.jarvis.vision.phase10;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DesktopFrameIngestControllerTest {

    private VisionEventEmitter emitter;
    private DemoModeProperties demoMode;
    private DesktopFrameIngestController controller;

    @BeforeEach
    void setUp() {
        emitter = mock(VisionEventEmitter.class);
        demoMode = new DemoModeProperties();
        controller = new DesktopFrameIngestController(emitter, demoMode);
    }

    private DesktopFrameIngestController.FrameIngestRequest req(String type) {
        return new DesktopFrameIngestController.FrameIngestRequest(
                "agent-1", "owner", type, "file:///tmp/frame.bin",
                "Firefox - Diploma docs", Instant.now());
    }

    @Test
    void blankCaptureTypeIs400() {
        var resp = controller.ingest(new DesktopFrameIngestController.FrameIngestRequest(
                "a", "u", " ", null, null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(emitter, never()).frameCaptured(any(), any(), any(), any());
    }

    @Test
    void normalIngestEmitsFrameCapturedAndAccepts() {
        var resp = controller.ingest(req("webcam"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("accepted");
        assertThat(resp.getBody().frameId()).startsWith("frame-");
        verify(emitter, times(1)).frameCaptured(eq("agent-1"), eq("owner"), eq("webcam"), any());
    }

    @Test
    void demoModeRefusesAndEmitsBlock() {
        demoMode.setEnabled(true);
        var resp = controller.ingest(req("screen"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("refused");
        assertThat(resp.getBody().reason()).contains("demo");
        verify(emitter, never()).frameCaptured(any(), any(), any(), any());
        verify(emitter, times(1)).demoModeBlocked(eq("agent-1"), eq("owner"),
                eq("/api/v1/vision/frames"));
    }

    @Test
    void nullBodyIs400() {
        var resp = controller.ingest(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
