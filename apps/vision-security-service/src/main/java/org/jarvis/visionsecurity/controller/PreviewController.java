package org.jarvis.visionsecurity.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.service.CameraCaptureService;
import org.jarvis.visionsecurity.service.VisionPipelineService;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Live camera preview with detection/recognition overlays — a debug window so the
 * operator can see exactly what the vision pipeline sees and how each face is judged
 * (green = OWNER, red = UNKNOWN, yellow = UNCERTAIN, with the raw LBPH distance).
 *
 * <p>These endpoints are intentionally exposed without a JWT (added to
 * {@code getPublicEndpoints()} in SecurityConfig) so a host browser or ffplay can
 * open the feed directly. To avoid leaking the camera to the LAN or to in-cluster
 * pods, every request is hard-restricted to loopback callers — anything that is not
 * 127.0.0.1/::1 gets {@code 403}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/vision-security/preview")
@RequiredArgsConstructor
public class PreviewController {

    private static final int FRAME_INTERVAL_MS = 200;
    private static final int JPEG_QUALITY = 80;
    private static final String MJPEG_BOUNDARY = "frame";
    private static final double FONT_SCALE = 0.55;
    private static final int FONT_THICKNESS = 2;

    private final CameraCaptureService cameraCaptureService;
    private final VisionPipelineService pipelineService;

    @GetMapping(value = "/snapshot.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(
            @RequestParam(name = "user", required = false, defaultValue = "") String user,
            HttpServletRequest request) {
        if (!isLoopback(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] jpeg = grabAnnotatedJpeg(user);
        if (jpeg == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestParam(name = "user", required = false, defaultValue = "") String user,
            HttpServletRequest request) {
        if (!isLoopback(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        StreamingResponseBody body = out -> streamFrames(out, user);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY))
                .body(body);
    }

    private void streamFrames(OutputStream out, String user) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] jpeg = grabAnnotatedJpeg(user);
                if (jpeg != null) {
                    String header = "--" + MJPEG_BOUNDARY + "\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + jpeg.length + "\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(jpeg);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                Thread.sleep(FRAME_INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            // Client closed the connection (browser tab / ffplay window closed) — expected.
            log.debug("Preview stream closed: {}", ex.getMessage());
        }
    }

    private byte[] grabAnnotatedJpeg(String user) {
        Mat frame = null;
        Mat annotated = null;
        MatOfByte buffer = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
        try {
            frame = cameraCaptureService.captureFrame("vision preview");
            PipelineResult result = pipelineService.analyze(user, frame, null);
            annotated = frame.clone();
            drawOverlay(annotated, result, user);
            if (!Imgcodecs.imencode(".jpg", annotated, buffer, params)) {
                return null;
            }
            return buffer.toArray();
        } catch (IllegalStateException | IllegalArgumentException | IOException ex) {
            log.warn("Preview frame unavailable: {}", ex.getMessage());
            return null;
        } finally {
            if (frame != null) {
                frame.release();
            }
            if (annotated != null) {
                annotated.release();
            }
            buffer.release();
            params.release();
        }
    }

    private void drawOverlay(Mat target, PipelineResult result, String user) {
        for (FaceMatch match : result.faces()) {
            Scalar color = switch (match.verdict()) {
                case OWNER -> new Scalar(0, 220, 0);
                case UNKNOWN -> new Scalar(0, 0, 255);
                case UNCERTAIN -> new Scalar(0, 255, 255);
            };
            RectBox box = match.box();
            Rect rect = new Rect(box.x(), box.y(), box.width(), box.height());
            Imgproc.rectangle(target, rect.tl(), rect.br(), color, FONT_THICKNESS);
            String label = match.verdict() + " " + String.format("%.1f", match.confidence());
            Imgproc.putText(target, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, FONT_SCALE, color, FONT_THICKNESS);
        }

        String owner = user == null || user.isBlank() ? "<none>" : user;
        Imgproc.putText(target, "Decision: " + result.decision() + "  owner=" + owner,
                new Point(16, 26), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 255, 255), FONT_THICKNESS);
        Imgproc.putText(target, result.reason(),
                new Point(16, target.rows() - 16), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                new Scalar(220, 220, 220), 1);
    }

    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
