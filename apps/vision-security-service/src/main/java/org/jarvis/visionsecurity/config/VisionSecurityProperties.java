package org.jarvis.visionsecurity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vision-security")
public class VisionSecurityProperties {

    private Monitoring monitoring = new Monitoring();
    private Camera camera = new Camera();
    private Enrollment enrollment = new Enrollment();
    private Verification verification = new Verification();
    private Storage storage = new Storage();
    private Screen screen = new Screen();
    private Email email = new Email();
    private Gpu gpu = new Gpu();
    private Cv cv = new Cv();

    @Getter
    @Setter
    public static class Monitoring {
        private boolean autoStart = false;
        private long checkIntervalMs = 2_000L;
        private int debounceUnknownFrames = 3;
        private long alertCooldownSeconds = 60L;
        private int ownerGraceFrames = 2;
    }

    @Getter
    @Setter
    public static class Camera {
        private int deviceIndex = 0;
        private int width = 640;
        private int height = 480;
    }

    @Getter
    @Setter
    public static class Enrollment {
        private int sampleCount = 8;
        private long sampleSpacingMs = 700L;
        private long captureTimeoutSeconds = 30L;
        private double minFaceSharpness = 30.0;
        private double minFaceContrast = 25.0;
        private int maxDuplicateHashDistance = 4;
    }

    @Getter
    @Setter
    public static class Verification {
        private int normalizedFaceSize = 160;
        private double ownerThresholdMargin = 15.0;
        private double uncertainThresholdMargin = 30.0;
        private double fallbackOwnerThreshold = 70.0;
        private double fallbackUncertainThreshold = 100.0;
        private double minDetectionAreaRatio = 0.004;
        private double facePaddingRatio = 0.12;
        private double claheClipLimit = 2.5;
        private int claheGridSize = 8;
        private boolean enableEyeAlignment = true;
        private double detectionScaleFactor = 1.08;
        private int detectionMinNeighbors = 2;
        /**
         * When true and a frame contains both the owner and at least one unknown face,
         * the frame-level decision is escalated to UNKNOWN_PERSON rather than silenced
         * as OWNER_PRESENT. Default is security-first.
         */
        private boolean alertOnStrangerWithOwner = true;
        /**
         * Minimum mean brightness (0–255) required for a monitoring frame to be analysed.
         * Frames darker than this are reported as NO_FACE with reason "frame too dark"
         * to avoid wasting cycles on unusable inputs (covered lens, lights off).
         */
        private double minFrameBrightness = 8.0;
    }

    @Getter
    @Setter
    public static class Storage {
        private String root = System.getProperty("user.home") + "/.jarvis/data/vision-security";
        private int maxIncidentsPerUser = 50;
    }

    @Getter
    @Setter
    public static class Screen {
        private String ocrLanguage = "eng";
    }

    @Getter
    @Setter
    public static class Email {
        private String recipient = "";
        private String from = "";
        private String subjectPrefix = "[Jarvis Vision Security]";
    }

    @Getter
    @Setter
    public static class Gpu {
        private boolean preferIfAvailable = false;
    }

    /**
     * Local CV vertical-slice config. Only the {@code engine} key is
     * load-bearing at the moment; additional knobs (engine-specific
     * options, screen-context flags) live here so config can grow in one
     * place rather than being scattered across services.
     */
    @Getter
    @Setter
    public static class Cv {
        /**
         * Identifier of the OCR engine to use. Must match an
         * {@code OcrEngine#id()} (e.g. "tesseract"). When blank or
         * unknown, the first registered engine is used as a fallback.
         */
        private String engine = "tesseract";
        /**
         * When true, the screen-context endpoint also publishes a Kafka
         * event onto {@code jarvis.cv.screen_context.created}.
         * Defaults to true; ignored when Kafka is not configured.
         */
        private boolean publishScreenContextEvent = true;
        /**
         * Kafka topic for screen-context events. Centralised here so
         * downstream consumers (memory-service, planner, etc.) read the
         * same constant.
         */
        private String screenContextTopic = "jarvis.cv.screen_context.created";
        /** Local VLM (vision-language model) settings. */
        private Vlm vlm = new Vlm();
    }

    /**
     * Local vision-language-model adapter settings. {@code enabled=false}
     * by default — Jarvis never speaks to cloud vision APIs, and this
     * stays off until a local runtime (Ollama or llama.cpp/llava) is
     * explicitly configured.
     */
    @Getter
    @Setter
    public static class Vlm {
        private boolean enabled = false;
        /**
         * One of {@code disabled}, {@code ollama}, {@code llamacpp}. Any
         * other value is treated as {@code disabled} (the placeholder
         * adapter then keeps returning {@code NOT_CONFIGURED}).
         */
        private String provider = "disabled";
        /**
         * Base URL of the local VLM backend. Examples:
         * {@code http://localhost:11434} (Ollama),
         * {@code http://localhost:8080} (llama.cpp server).
         */
        private String endpoint = "";
        /**
         * Model name as known to the backend. Examples: {@code llava},
         * {@code minicpm-v}, {@code qwen2-vl-7b-instruct.gguf}.
         */
        private String model = "";
        /** Request timeout. Hard upper bound for a single call. */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(30);
        private int maxTokens = 512;
        private double temperature = 0.2;
        /**
         * When true, the OCR text extracted from the screenshot is appended
         * to the VLM prompt as grounding context. Default true.
         */
        private boolean includeOcrContext = true;
        /**
         * When true, the screenshot image is attached to the VLM request.
         * Set false to run a text-only model over just the OCR context
         * (no image bytes leave the process). Default true.
         */
        private boolean includeScreenshot = true;
    }
}
