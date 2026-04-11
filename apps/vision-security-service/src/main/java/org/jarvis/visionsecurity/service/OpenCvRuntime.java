package org.jarvis.visionsecurity.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Component
public class OpenCvRuntime {

    @Getter
    private final Path faceCascadePath;

    @Getter
    private final Path altFaceCascadePath;

    @Getter
    private final Path eyeCascadePath;

    public OpenCvRuntime() throws IOException {
        Loader.load(opencv_java.class);
        this.faceCascadePath = extractCascade("haarcascade_frontalface_default.xml");
        this.altFaceCascadePath = extractCascadeOptional("haarcascade_frontalface_alt2.xml");
        this.eyeCascadePath = extractCascadeOptional("haarcascade_eye.xml");
        log.info("OpenCV runtime ready: altFaceCascade={}, eyeCascade={}",
                altFaceCascadePath != null ? "available" : "missing",
                eyeCascadePath != null ? "available" : "missing");
    }

    private Path extractCascade(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("cascade/" + filename);
        if (!resource.exists()) {
            throw new IOException("Missing cascade resource: " + filename);
        }
        return copyToTemp(resource, filename);
    }

    private Path extractCascadeOptional(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource("cascade/" + filename);
            if (!resource.exists()) {
                log.warn("Optional cascade not found: {}", filename);
                return null;
            }
            return copyToTemp(resource, filename);
        } catch (IOException ex) {
            log.warn("Failed to load optional cascade {}: {}", filename, ex.getMessage());
            return null;
        }
    }

    private Path copyToTemp(ClassPathResource resource, String filename) throws IOException {
        Path target = Files.createTempFile("jarvis-" + filename.replace(".xml", "-"), ".xml");
        target.toFile().deleteOnExit();
        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
