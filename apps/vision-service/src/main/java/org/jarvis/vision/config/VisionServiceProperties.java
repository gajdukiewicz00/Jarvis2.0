package org.jarvis.vision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "vision")
public class VisionServiceProperties {

    private boolean enabled = true;
    private String detectorProvider = "opencv-haarcascade";
    private String verifierProvider = "embedding-cosine-mvp";
    private double similarityThreshold = 0.82d;
    private int minimumFaceSizePixels = 80;
    private Path faceCascadePath;
    private Path ownerReferenceDirectory = Path.of(System.getProperty("user.home"),
            ".jarvis", "security-monitoring", "owner-reference");
    private List<String> referenceExtensions = new ArrayList<>(List.of("jpg", "jpeg", "png", "bmp"));
    private final Embedding embedding = new Embedding();
    private final Pipeline pipeline = new Pipeline();
    private final Enrollment enrollment = new Enrollment();
    private final Alignment alignment = new Alignment();
    private final ReferenceCache referenceCache = new ReferenceCache();
    private final Liveness liveness = new Liveness();
    private final Screen screen = new Screen();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDetectorProvider() {
        return detectorProvider;
    }

    public void setDetectorProvider(String detectorProvider) {
        this.detectorProvider = detectorProvider == null || detectorProvider.isBlank()
                ? "opencv-haarcascade" : detectorProvider.trim();
    }

    public String getVerifierProvider() {
        return verifierProvider;
    }

    public void setVerifierProvider(String verifierProvider) {
        this.verifierProvider = verifierProvider == null || verifierProvider.isBlank()
                ? "embedding-cosine-mvp" : verifierProvider.trim();
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public int getMinimumFaceSizePixels() {
        return minimumFaceSizePixels;
    }

    public void setMinimumFaceSizePixels(int minimumFaceSizePixels) {
        this.minimumFaceSizePixels = Math.max(32, minimumFaceSizePixels);
    }

    public Path getFaceCascadePath() {
        return faceCascadePath;
    }

    public void setFaceCascadePath(Path faceCascadePath) {
        this.faceCascadePath = faceCascadePath;
    }

    public Path getOwnerReferenceDirectory() {
        return ownerReferenceDirectory;
    }

    public void setOwnerReferenceDirectory(Path ownerReferenceDirectory) {
        this.ownerReferenceDirectory = ownerReferenceDirectory == null
                ? Path.of(System.getProperty("user.home"), ".jarvis", "security-monitoring", "owner-reference")
                : ownerReferenceDirectory;
    }

    public List<String> getReferenceExtensions() {
        return List.copyOf(referenceExtensions);
    }

    public void setReferenceExtensions(List<String> referenceExtensions) {
        if (referenceExtensions == null || referenceExtensions.isEmpty()) {
            this.referenceExtensions = new ArrayList<>(List.of("jpg", "jpeg", "png", "bmp"));
            return;
        }
        this.referenceExtensions = referenceExtensions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public void setReferenceExtensions(String referenceExtensions) {
        if (referenceExtensions == null || referenceExtensions.isBlank()) {
            setReferenceExtensions(List.of());
            return;
        }
        setReferenceExtensions(Arrays.stream(referenceExtensions.split(",")).toList());
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public Enrollment getEnrollment() {
        return enrollment;
    }

    public Alignment getAlignment() {
        return alignment;
    }

    public ReferenceCache getReferenceCache() {
        return referenceCache;
    }

    public Liveness getLiveness() {
        return liveness;
    }

    public Screen getScreen() {
        return screen;
    }

    public static class Embedding {

        private int faceImageSize = 64;
        private int poolingGridSize = 8;
        private final Model model = new Model();

        public int getFaceImageSize() {
            return faceImageSize;
        }

        public void setFaceImageSize(int faceImageSize) {
            this.faceImageSize = Math.max(16, faceImageSize);
        }

        public int getPoolingGridSize() {
            return poolingGridSize;
        }

        public void setPoolingGridSize(int poolingGridSize) {
            this.poolingGridSize = Math.max(2, poolingGridSize);
        }

        public Model getModel() {
            return model;
        }
    }

    public static class Pipeline {

        private double gamma = 1.10d;
        private int blurKernelSize = 5;
        private int adaptiveThresholdBlockSize = 21;
        private double adaptiveThresholdConstant = 5.0d;
        private int cannyLowThreshold = 40;
        private int cannyHighThreshold = 120;
        private int morphologyKernelSize = 5;
        private int minimumCandidateAreaPixels = 400;

        public double getGamma() {
            return gamma;
        }

        public void setGamma(double gamma) {
            this.gamma = gamma <= 0.0d ? 1.0d : gamma;
        }

        public int getBlurKernelSize() {
            return blurKernelSize;
        }

        public void setBlurKernelSize(int blurKernelSize) {
            this.blurKernelSize = oddAtLeast(blurKernelSize, 1);
        }

        public int getAdaptiveThresholdBlockSize() {
            return adaptiveThresholdBlockSize;
        }

        public void setAdaptiveThresholdBlockSize(int adaptiveThresholdBlockSize) {
            this.adaptiveThresholdBlockSize = oddAtLeast(adaptiveThresholdBlockSize, 3);
        }

        public double getAdaptiveThresholdConstant() {
            return adaptiveThresholdConstant;
        }

        public void setAdaptiveThresholdConstant(double adaptiveThresholdConstant) {
            this.adaptiveThresholdConstant = adaptiveThresholdConstant;
        }

        public int getCannyLowThreshold() {
            return cannyLowThreshold;
        }

        public void setCannyLowThreshold(int cannyLowThreshold) {
            this.cannyLowThreshold = Math.max(0, cannyLowThreshold);
        }

        public int getCannyHighThreshold() {
            return cannyHighThreshold;
        }

        public void setCannyHighThreshold(int cannyHighThreshold) {
            this.cannyHighThreshold = Math.max(this.cannyLowThreshold + 1, cannyHighThreshold);
        }

        public int getMorphologyKernelSize() {
            return morphologyKernelSize;
        }

        public void setMorphologyKernelSize(int morphologyKernelSize) {
            this.morphologyKernelSize = oddAtLeast(morphologyKernelSize, 1);
        }

        public int getMinimumCandidateAreaPixels() {
            return minimumCandidateAreaPixels;
        }

        public void setMinimumCandidateAreaPixels(int minimumCandidateAreaPixels) {
            this.minimumCandidateAreaPixels = Math.max(16, minimumCandidateAreaPixels);
        }

        private static int oddAtLeast(int value, int minimum) {
            int bounded = Math.max(minimum, value);
            return bounded % 2 == 0 ? bounded + 1 : bounded;
        }
    }

    public static class Model {

        private String backend = "opencv-dnn-onnx";
        private String profile = "arcface-112-rgb";
        private Path path;
        private int inputWidth = 112;
        private int inputHeight = 112;
        private double scale = 0.0078125d;
        private double meanBlue = 127.5d;
        private double meanGreen = 127.5d;
        private double meanRed = 127.5d;
        private boolean swapRedBlue = true;
        private String outputName = "";
        private int expectedEmbeddingLength = 512;
        private boolean validateOnStartup = true;
        private Double similarityThreshold;

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend == null || backend.isBlank()
                    ? "opencv-dnn-onnx"
                    : backend.trim();
        }

        public String getProfile() {
            return profile;
        }

        public void setProfile(String profile) {
            this.profile = profile == null || profile.isBlank()
                    ? "arcface-112-rgb"
                    : profile.trim();
        }

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }

        public int getInputWidth() {
            return inputWidth;
        }

        public void setInputWidth(int inputWidth) {
            this.inputWidth = Math.max(16, inputWidth);
        }

        public int getInputHeight() {
            return inputHeight;
        }

        public void setInputHeight(int inputHeight) {
            this.inputHeight = Math.max(16, inputHeight);
        }

        public double getScale() {
            return scale;
        }

        public void setScale(double scale) {
            this.scale = scale;
        }

        public double getMeanBlue() {
            return meanBlue;
        }

        public void setMeanBlue(double meanBlue) {
            this.meanBlue = meanBlue;
        }

        public double getMeanGreen() {
            return meanGreen;
        }

        public void setMeanGreen(double meanGreen) {
            this.meanGreen = meanGreen;
        }

        public double getMeanRed() {
            return meanRed;
        }

        public void setMeanRed(double meanRed) {
            this.meanRed = meanRed;
        }

        public boolean isSwapRedBlue() {
            return swapRedBlue;
        }

        public void setSwapRedBlue(boolean swapRedBlue) {
            this.swapRedBlue = swapRedBlue;
        }

        public String getOutputName() {
            return outputName;
        }

        public void setOutputName(String outputName) {
            this.outputName = outputName == null ? "" : outputName.trim();
        }

        public int getExpectedEmbeddingLength() {
            return expectedEmbeddingLength;
        }

        public void setExpectedEmbeddingLength(int expectedEmbeddingLength) {
            this.expectedEmbeddingLength = Math.max(1, expectedEmbeddingLength);
        }

        public boolean isValidateOnStartup() {
            return validateOnStartup;
        }

        public void setValidateOnStartup(boolean validateOnStartup) {
            this.validateOnStartup = validateOnStartup;
        }

        public Double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(Double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }

    public static class Enrollment {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Alignment {

        private boolean enabled = true;
        private double minimumEyeSeparationRatio = 0.18d;
        private double maximumRollAngleDegrees = 20.0d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinimumEyeSeparationRatio() {
            return minimumEyeSeparationRatio;
        }

        public void setMinimumEyeSeparationRatio(double minimumEyeSeparationRatio) {
            this.minimumEyeSeparationRatio = clamp(minimumEyeSeparationRatio, 0.05d, 0.45d);
        }

        public double getMaximumRollAngleDegrees() {
            return maximumRollAngleDegrees;
        }

        public void setMaximumRollAngleDegrees(double maximumRollAngleDegrees) {
            this.maximumRollAngleDegrees = clamp(maximumRollAngleDegrees, 1.0d, 45.0d);
        }
    }

    public static class ReferenceCache {

        private boolean prewarmOnStartup = false;

        public boolean isPrewarmOnStartup() {
            return prewarmOnStartup;
        }

        public void setPrewarmOnStartup(boolean prewarmOnStartup) {
            this.prewarmOnStartup = prewarmOnStartup;
        }
    }

    public static class Liveness {

        private boolean enabled = true;
        private double minimumSharpness = 18.0d;
        private double minimumContrast = 18.0d;
        private double passThreshold = 0.55d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinimumSharpness() {
            return minimumSharpness;
        }

        public void setMinimumSharpness(double minimumSharpness) {
            this.minimumSharpness = Math.max(1.0d, minimumSharpness);
        }

        public double getMinimumContrast() {
            return minimumContrast;
        }

        public void setMinimumContrast(double minimumContrast) {
            this.minimumContrast = Math.max(1.0d, minimumContrast);
        }

        public double getPassThreshold() {
            return passThreshold;
        }

        public void setPassThreshold(double passThreshold) {
            this.passThreshold = clamp(passThreshold, 0.05d, 0.99d);
        }
    }

    public static class Screen {

        private boolean enabled = true;
        private double ocrReadyContrastThreshold = 22.0d;
        private double textEdgeDensityThreshold = 0.08d;
        private double sensitiveThreshold = 0.60d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getOcrReadyContrastThreshold() {
            return ocrReadyContrastThreshold;
        }

        public void setOcrReadyContrastThreshold(double ocrReadyContrastThreshold) {
            this.ocrReadyContrastThreshold = Math.max(1.0d, ocrReadyContrastThreshold);
        }

        public double getTextEdgeDensityThreshold() {
            return textEdgeDensityThreshold;
        }

        public void setTextEdgeDensityThreshold(double textEdgeDensityThreshold) {
            this.textEdgeDensityThreshold = clamp(textEdgeDensityThreshold, 0.01d, 0.95d);
        }

        public double getSensitiveThreshold() {
            return sensitiveThreshold;
        }

        public void setSensitiveThreshold(double sensitiveThreshold) {
            this.sensitiveThreshold = clamp(sensitiveThreshold, 0.05d, 0.99d);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
