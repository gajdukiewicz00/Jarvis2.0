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
}
