package org.jarvis.visionsecurity.service;

import lombok.Getter;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class OpenCvRuntime {

    @Getter
    private final Path faceCascadePath;

    public OpenCvRuntime() throws IOException {
        Loader.load(opencv_java.class);
        this.faceCascadePath = extractCascade();
    }

    private Path extractCascade() throws IOException {
        ClassPathResource resource = new ClassPathResource("cascade/haarcascade_frontalface_default.xml");
        if (!resource.exists()) {
            throw new IOException("Missing cascade resource: haarcascade_frontalface_default.xml");
        }

        Path target = Files.createTempFile("jarvis-face-cascade-", ".xml");
        target.toFile().deleteOnExit();
        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
