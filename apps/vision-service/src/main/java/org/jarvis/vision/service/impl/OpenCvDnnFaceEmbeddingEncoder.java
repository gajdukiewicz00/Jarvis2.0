package org.jarvis.vision.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component("openCvDnnFaceEmbeddingEncoder")
public class OpenCvDnnFaceEmbeddingEncoder implements FaceEmbeddingEncoder {

    private static final String BACKEND = "opencv-dnn-onnx";

    private final VisionServiceProperties properties;
    private final OpenCvRuntime openCvRuntime;
    private final AtomicBoolean initialized = new AtomicBoolean();

    private volatile Net net;
    private volatile String failureMessage;
    private volatile boolean warmupValidated;
    private volatile int actualEmbeddingLength;

    public OpenCvDnnFaceEmbeddingEncoder(VisionServiceProperties properties, OpenCvRuntime openCvRuntime) {
        this.properties = properties;
        this.openCvRuntime = openCvRuntime;
    }

    @Override
    public String encoderId() {
        return BACKEND;
    }

    @Override
    public String cacheKey() {
        VisionServiceProperties.Model model = properties.getEmbedding().getModel();
        return encoderId()
                + "::profile=" + model.getProfile()
                + "::path=" + (model.getPath() == null ? "" : model.getPath().toAbsolutePath())
                + "::output=" + model.getOutputName()
                + "::input=" + model.getInputWidth() + "x" + model.getInputHeight();
    }

    @Override
    public double[] encode(BufferedImage faceImage) {
        initializeIfNeeded();
        if (!isAvailable()) {
            throw new IllegalStateException(availabilityMessage());
        }

        Mat image = null;
        Mat blob = null;
        Mat embedding = null;
        try {
            image = OpenCvImageUtils.toMat(faceImage);
            blob = blobFromImage(image);
            embedding = forward(blob);
            double[] normalized = normalize(flatten(embedding));
            actualEmbeddingLength = normalized.length;
            validateEmbeddingLength(normalized.length);
            return normalized;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to run embedding model: " + exception.getMessage(), exception);
        } finally {
            release(embedding, blob, image);
        }
    }

    @Override
    public boolean isAvailable() {
        initializeIfNeeded();
        return failureMessage == null && net != null;
    }

    @Override
    public String availabilityMessage() {
        initializeIfNeeded();
        return failureMessage == null ? "" : failureMessage;
    }

    @Override
    public Map<String, String> statusDetails() {
        Map<String, String> details = new LinkedHashMap<>(FaceEmbeddingEncoder.super.statusDetails());
        VisionServiceProperties.Model model = properties.getEmbedding().getModel();
        boolean modelConfigured = model.getPath() != null && !model.getPath().toString().isBlank();
        details.put("embeddingBackendConfigured", model.getBackend());
        details.put("embeddingModelProfile", model.getProfile());
        details.put("embeddingModelPath", model.getPath() == null ? "" : model.getPath().toString());
        details.put("embeddingModelConfigured", String.valueOf(modelConfigured));
        details.put("embeddingModelLoaded", String.valueOf(isAvailable()));
        details.put("embeddingModelValidated", String.valueOf(warmupValidated || !model.isValidateOnStartup()));
        details.put("embeddingInputWidth", String.valueOf(model.getInputWidth()));
        details.put("embeddingInputHeight", String.valueOf(model.getInputHeight()));
        details.put("embeddingExpectedLength", String.valueOf(model.getExpectedEmbeddingLength()));
        details.put("embeddingActualLength", String.valueOf(actualEmbeddingLength));
        details.put("embeddingPreprocessScale", format(model.getScale()));
        details.put("embeddingPreprocessMeanBlue", format(model.getMeanBlue()));
        details.put("embeddingPreprocessMeanGreen", format(model.getMeanGreen()));
        details.put("embeddingPreprocessMeanRed", format(model.getMeanRed()));
        details.put("embeddingPreprocessSwapRB", String.valueOf(model.isSwapRedBlue()));
        if (model.getSimilarityThreshold() != null) {
            details.put("embeddingSimilarityThresholdOverride", format(model.getSimilarityThreshold()));
        }
        if (!availabilityMessage().isBlank()) {
            details.put("embeddingModelMessage", availabilityMessage());
        }
        details.put("embeddingModelFormat",
                "ONNX float vector with ArcFace-style 112x112 RGB preprocessing");
        return details;
    }

    private void initializeIfNeeded() {
        if (initialized.get()) {
            return;
        }
        synchronized (this) {
            if (initialized.get()) {
                return;
            }

            VisionServiceProperties.Model model = properties.getEmbedding().getModel();
            if (!BACKEND.equals(model.getBackend())) {
                failureMessage = "Unsupported embedding backend: " + model.getBackend();
                initialized.set(true);
                return;
            }
            if (!"arcface-112-rgb".equals(model.getProfile())) {
                failureMessage = "Unsupported embedding profile: " + model.getProfile();
                initialized.set(true);
                return;
            }
            if (model.getInputWidth() != 112 || model.getInputHeight() != 112) {
                failureMessage = "arcface-112-rgb profile requires 112x112 model input";
                initialized.set(true);
                return;
            }
            if (!openCvRuntime.isAvailable()) {
                failureMessage = openCvRuntime.failureMessage();
                initialized.set(true);
                return;
            }

            Path modelPath = model.getPath();
            if (modelPath == null || modelPath.toString().isBlank()) {
                failureMessage = "Embedding model path is not configured";
                initialized.set(true);
                return;
            }
            if (!Files.isRegularFile(modelPath)) {
                failureMessage = "Embedding model file does not exist: " + modelPath;
                initialized.set(true);
                return;
            }

            try {
                net = Dnn.readNetFromONNX(modelPath.toString());
                if (net.empty()) {
                    failureMessage = "Embedding model failed to load: " + modelPath;
                } else {
                    net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
                    net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
                    if (model.isValidateOnStartup()) {
                        runWarmupValidation();
                    }
                    if (failureMessage == null) {
                        log.info("Loaded face embedding encoder from {} using profile {}", modelPath, model.getProfile());
                    }
                }
            } catch (Throwable throwable) {
                failureMessage = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
                log.warn("Failed to initialize model-backed face embedding encoder: {}", failureMessage);
            } finally {
                initialized.set(true);
            }
        }
    }

    private void runWarmupValidation() {
        Mat zeroImage = null;
        Mat blob = null;
        Mat output = null;
        try {
            VisionServiceProperties.Model model = properties.getEmbedding().getModel();
            zeroImage = new Mat(model.getInputHeight(), model.getInputWidth(), CvType.CV_8UC3, new Scalar(0, 0, 0));
            blob = blobFromImage(zeroImage);
            output = forward(blob);
            double[] values = normalize(flatten(output));
            actualEmbeddingLength = values.length;
            validateEmbeddingLength(values.length);
            warmupValidated = true;
        } catch (Exception exception) {
            failureMessage = "Embedding model warmup failed: " + exception.getMessage();
            log.warn("Embedding model warmup failed: {}", exception.getMessage());
        } finally {
            release(output, blob, zeroImage);
        }
    }

    private void validateEmbeddingLength(int length) {
        int expected = properties.getEmbedding().getModel().getExpectedEmbeddingLength();
        if (length != expected) {
            throw new IllegalStateException(
                    "Embedding length mismatch. Expected " + expected + " but got " + length);
        }
    }

    private Mat blobFromImage(Mat image) {
        VisionServiceProperties.Model model = properties.getEmbedding().getModel();
        return Dnn.blobFromImage(
                image,
                model.getScale(),
                new Size(model.getInputWidth(), model.getInputHeight()),
                new Scalar(model.getMeanBlue(), model.getMeanGreen(), model.getMeanRed()),
                model.isSwapRedBlue(),
                false);
    }

    private Mat forward(Mat blob) {
        VisionServiceProperties.Model model = properties.getEmbedding().getModel();
        synchronized (this) {
            net.setInput(blob);
            return model.getOutputName().isBlank()
                    ? net.forward()
                    : net.forward(model.getOutputName());
        }
    }

    private static double[] flatten(Mat mat) {
        Mat row = mat.reshape(1, 1);
        float[] values = new float[(int) row.total()];
        row.get(0, 0, values);
        double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    private static double[] normalize(double[] vector) {
        double norm = 0.0d;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0d) {
            return vector;
        }
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    private static void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
