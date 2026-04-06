package org.jarvis.vision.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VisionServicePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "vision.enabled=false",
                    "vision.detector-provider=test-detector",
                    "vision.verifier-provider=embedding-cosine-model",
                    "vision.similarity-threshold=0.91",
                    "vision.minimum-face-size-pixels=96",
                    "vision.face-cascade-path=/tmp/cascade.xml",
                    "vision.owner-reference-directory=/tmp/owner",
                    "vision.reference-extensions=png,jpg",
                    "vision.pipeline.gamma=1.25",
                    "vision.pipeline.blur-kernel-size=7",
                    "vision.pipeline.adaptive-threshold-block-size=25",
                    "vision.pipeline.adaptive-threshold-constant=4.0",
                    "vision.pipeline.canny-low-threshold=30",
                    "vision.pipeline.canny-high-threshold=110",
                    "vision.pipeline.morphology-kernel-size=7",
                    "vision.pipeline.minimum-candidate-area-pixels=600",
                    "vision.embedding.face-image-size=48",
                    "vision.embedding.pooling-grid-size=6",
                    "vision.embedding.model.backend=opencv-dnn-onnx",
                    "vision.embedding.model.profile=arcface-112-rgb",
                    "vision.embedding.model.path=/tmp/face-embedder.onnx",
                    "vision.embedding.model.input-width=128",
                    "vision.embedding.model.input-height=128",
                    "vision.embedding.model.scale=0.0078125",
                    "vision.embedding.model.mean-blue=127.5",
                    "vision.embedding.model.mean-green=127.5",
                    "vision.embedding.model.mean-red=127.5",
                    "vision.embedding.model.swap-red-blue=false",
                    "vision.embedding.model.output-name=embedding",
                    "vision.embedding.model.expected-embedding-length=256",
                    "vision.embedding.model.validate-on-startup=false",
                    "vision.embedding.model.similarity-threshold=0.87",
                    "vision.alignment.enabled=false",
                    "vision.alignment.minimum-eye-separation-ratio=0.22",
                    "vision.alignment.maximum-roll-angle-degrees=15",
                    "vision.reference-cache.prewarm-on-startup=true",
                    "vision.liveness.enabled=false",
                    "vision.liveness.minimum-sharpness=25",
                    "vision.liveness.minimum-contrast=30",
                    "vision.liveness.pass-threshold=0.61",
                    "vision.screen.enabled=false",
                    "vision.screen.ocr-ready-contrast-threshold=30",
                    "vision.screen.text-edge-density-threshold=0.11",
                    "vision.screen.sensitive-threshold=0.71",
                    "vision.enrollment.enabled=false");

    @Test
    void bindsVisionServiceProperties() {
        contextRunner.run(context -> {
            VisionServiceProperties properties = context.getBean(VisionServiceProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getDetectorProvider()).isEqualTo("test-detector");
            assertThat(properties.getVerifierProvider()).isEqualTo("embedding-cosine-model");
            assertThat(properties.getSimilarityThreshold()).isEqualTo(0.91d);
            assertThat(properties.getMinimumFaceSizePixels()).isEqualTo(96);
            assertThat(properties.getFaceCascadePath()).isEqualTo(Path.of("/tmp/cascade.xml"));
            assertThat(properties.getOwnerReferenceDirectory()).isEqualTo(Path.of("/tmp/owner"));
            assertThat(properties.getReferenceExtensions()).containsExactly("png", "jpg");
            assertThat(properties.getPipeline().getGamma()).isEqualTo(1.25d);
            assertThat(properties.getPipeline().getBlurKernelSize()).isEqualTo(7);
            assertThat(properties.getPipeline().getAdaptiveThresholdBlockSize()).isEqualTo(25);
            assertThat(properties.getPipeline().getAdaptiveThresholdConstant()).isEqualTo(4.0d);
            assertThat(properties.getPipeline().getCannyLowThreshold()).isEqualTo(30);
            assertThat(properties.getPipeline().getCannyHighThreshold()).isEqualTo(110);
            assertThat(properties.getPipeline().getMorphologyKernelSize()).isEqualTo(7);
            assertThat(properties.getPipeline().getMinimumCandidateAreaPixels()).isEqualTo(600);
            assertThat(properties.getEmbedding().getFaceImageSize()).isEqualTo(48);
            assertThat(properties.getEmbedding().getPoolingGridSize()).isEqualTo(6);
            assertThat(properties.getEmbedding().getModel().getBackend()).isEqualTo("opencv-dnn-onnx");
            assertThat(properties.getEmbedding().getModel().getProfile()).isEqualTo("arcface-112-rgb");
            assertThat(properties.getEmbedding().getModel().getPath()).isEqualTo(Path.of("/tmp/face-embedder.onnx"));
            assertThat(properties.getEmbedding().getModel().getInputWidth()).isEqualTo(128);
            assertThat(properties.getEmbedding().getModel().getInputHeight()).isEqualTo(128);
            assertThat(properties.getEmbedding().getModel().getScale()).isEqualTo(0.0078125d);
            assertThat(properties.getEmbedding().getModel().getMeanBlue()).isEqualTo(127.5d);
            assertThat(properties.getEmbedding().getModel().getMeanGreen()).isEqualTo(127.5d);
            assertThat(properties.getEmbedding().getModel().getMeanRed()).isEqualTo(127.5d);
            assertThat(properties.getEmbedding().getModel().isSwapRedBlue()).isFalse();
            assertThat(properties.getEmbedding().getModel().getOutputName()).isEqualTo("embedding");
            assertThat(properties.getEmbedding().getModel().getExpectedEmbeddingLength()).isEqualTo(256);
            assertThat(properties.getEmbedding().getModel().isValidateOnStartup()).isFalse();
            assertThat(properties.getEmbedding().getModel().getSimilarityThreshold()).isEqualTo(0.87d);
            assertThat(properties.getAlignment().isEnabled()).isFalse();
            assertThat(properties.getAlignment().getMinimumEyeSeparationRatio()).isEqualTo(0.22d);
            assertThat(properties.getAlignment().getMaximumRollAngleDegrees()).isEqualTo(15.0d);
            assertThat(properties.getReferenceCache().isPrewarmOnStartup()).isTrue();
            assertThat(properties.getLiveness().isEnabled()).isFalse();
            assertThat(properties.getLiveness().getMinimumSharpness()).isEqualTo(25.0d);
            assertThat(properties.getLiveness().getMinimumContrast()).isEqualTo(30.0d);
            assertThat(properties.getLiveness().getPassThreshold()).isEqualTo(0.61d);
            assertThat(properties.getScreen().isEnabled()).isFalse();
            assertThat(properties.getScreen().getOcrReadyContrastThreshold()).isEqualTo(30.0d);
            assertThat(properties.getScreen().getTextEdgeDensityThreshold()).isEqualTo(0.11d);
            assertThat(properties.getScreen().getSensitiveThreshold()).isEqualTo(0.71d);
            assertThat(properties.getEnrollment().isEnabled()).isFalse();
        });
    }

    @EnableConfigurationProperties(VisionServiceProperties.class)
    static class TestConfig {
    }
}
