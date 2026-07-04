package org.jarvis.security.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Privacy / panic mode — a single safe, reversible flag. Other components
 * (proactive speaker, screen capture, mic) can read it to go quiet on demand.
 * Non-destructive: it never touches tokens or accounts.
 */
@Service
public class PrivacyModeService {

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Instant since;

    public boolean isActive() {
        return active.get();
    }

    public void enable() {
        if (active.compareAndSet(false, true)) {
            since = Instant.now();
        }
    }

    public void disable() {
        active.set(false);
        since = null;
    }

    public Instant since() {
        return since;
    }
}
