package org.jarvis.memory.obsidian;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 9 — vault location + layout configuration.
 *
 * <p>Defaults follow SPEC-1 § "Obsidian Vault Role". The vault path
 * supports {@code ~} expansion against {@code $HOME}. When
 * {@link #enabled} is false the vault writer becomes a no-op so the
 * service can run without a populated vault (CI / non-owner host).</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.memory.obsidian")
public class ObsidianVaultProperties {

    private boolean enabled = true;
    private String vaultPath = "~/JarvisVault";
    private String deletedLogSubdir = "06_System/deleted-memory-log";
    private String dailySubdir = "01_Daily";
    private String inboxSubdir = "00_Inbox";
}
