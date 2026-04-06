package org.jarvis.vision.service.impl;

import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class OpenCvRuntime {

    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile String failureMessage;

    public boolean isAvailable() {
        initializeIfNeeded();
        return failureMessage == null;
    }

    public String failureMessage() {
        initializeIfNeeded();
        return failureMessage;
    }

    private void initializeIfNeeded() {
        if (initialized.get()) {
            return;
        }
        synchronized (this) {
            if (initialized.get()) {
                return;
            }
            try {
                OpenCV.loadLocally();
                log.info("OpenCV runtime loaded for vision-service");
            } catch (Throwable throwable) {
                failureMessage = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
                log.warn("OpenCV runtime unavailable in vision-service: {}", failureMessage);
            } finally {
                initialized.set(true);
            }
        }
    }
}
