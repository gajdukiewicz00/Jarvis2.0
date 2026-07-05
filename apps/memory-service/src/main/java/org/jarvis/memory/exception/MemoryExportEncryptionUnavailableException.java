package org.jarvis.memory.exception;

/**
 * Roadmap P1 #9 — thrown by encrypted export/import when
 * {@code jarvis.memory.export.encryption-key-base64} is not configured, or
 * when a supplied encrypted payload cannot be decrypted (wrong/rotated key,
 * corrupted ciphertext).
 */
public class MemoryExportEncryptionUnavailableException extends RuntimeException {

    public MemoryExportEncryptionUnavailableException(String message) {
        super(message);
    }

    public MemoryExportEncryptionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
