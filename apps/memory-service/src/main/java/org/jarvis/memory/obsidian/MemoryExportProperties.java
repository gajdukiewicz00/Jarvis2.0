package org.jarvis.memory.obsidian;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Roadmap P1 #9 — bulk export/import ({@code jarvis.memory.export.*}).
 *
 * <p>Encryption is optional and flagged: when {@link #encryptionKeyBase64}
 * is blank (the default), {@code /export/encrypted} and
 * {@code /import/encrypted} refuse to run rather than silently writing
 * plaintext takeout data under an "encrypted" label.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.memory.export")
public class MemoryExportProperties {

    /** Base64-encoded AES-256 key (32 raw bytes). Blank = encryption disabled. */
    private String encryptionKeyBase64 = "";

    public boolean isEncryptionConfigured() {
        return encryptionKeyBase64 != null && !encryptionKeyBase64.isBlank();
    }
}
