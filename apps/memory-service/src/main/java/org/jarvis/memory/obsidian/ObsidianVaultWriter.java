package org.jarvis.memory.obsidian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Phase 9 — writes Markdown into the operator's Obsidian vault.
 *
 * <p>Atomic write pattern: render to a temp file in the same directory,
 * then {@code Files.move} with {@code ATOMIC_MOVE} + {@code REPLACE_EXISTING}.
 * Directories are created on demand. {@code ~} in the configured vault
 * path is expanded against {@code $HOME}.</p>
 *
 * <p>When {@link ObsidianVaultProperties#isEnabled()} is false the
 * writer logs at INFO and returns {@code null} so the service can run
 * in environments without a vault (CI, demo / non-owner hosts).</p>
 */
@Slf4j
@Component
public class ObsidianVaultWriter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int MAX_SLUG_LENGTH = 60;

    private final ObsidianVaultProperties properties;
    private final ObsidianMarkdownRenderer renderer;

    public ObsidianVaultWriter(ObsidianVaultProperties properties,
                               ObsidianMarkdownRenderer renderer) {
        this.properties = properties;
        this.renderer = renderer;
    }

    public Path vaultRoot() {
        return resolveVaultRoot(properties.getVaultPath());
    }

    /**
     * Write (or overwrite) the Markdown for a note. Returns the vault-relative
     * path so the caller can store it in {@code memory_notes.vault_relative_path},
     * or {@code null} when vault writing is disabled.
     */
    public String write(MemoryNoteEntity note) {
        if (!properties.isEnabled()) {
            log.info("[{}] obsidian writer disabled — skipping vault write", note.getMemoryId());
            return null;
        }
        MemoryCategory category = MemoryCategory.fromString(note.getCategory());
        String filename = buildFilename(note.getCreatedAt(), note.getTitle(), note.getMemoryId());
        Path relativeDir = Paths.get(category.relativeDirectory());
        Path absoluteDir = vaultRoot().resolve(relativeDir);
        Path target = absoluteDir.resolve(filename);
        String content = renderer.render(note);
        try {
            atomicWrite(target, content);
            Path relative = relativeDir.resolve(filename);
            log.info("[{}] vault note written: {}", note.getMemoryId(), relative);
            return relative.toString();
        } catch (IOException ex) {
            log.error("[{}] vault write failed: {}", note.getMemoryId(), ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Write the deletion tombstone under {@code 06_System/deleted-memory-log/{YYYY-MM-DD}/}
     * and return its vault-relative path. Caller is responsible for removing the
     * original active-memory file (see {@link #removeIfPresent(String)}).
     */
    public String writeTombstone(MemoryNoteEntity note, String reason, String actor) {
        if (!properties.isEnabled()) return null;
        Instant now = Instant.now();
        String dayDir = DATE_FMT.format(now);
        Path relativeDir = Paths.get(properties.getDeletedLogSubdir(), dayDir);
        Path absoluteDir = vaultRoot().resolve(relativeDir);
        String filename = note.getMemoryId() + ".md";
        Path target = absoluteDir.resolve(filename);
        try {
            atomicWrite(target, renderer.renderTombstone(note, reason, actor));
            Path relative = relativeDir.resolve(filename);
            log.info("[{}] tombstone written: {}", note.getMemoryId(), relative);
            return relative.toString();
        } catch (IOException ex) {
            log.error("[{}] tombstone write failed: {}", note.getMemoryId(), ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Write arbitrary markdown to a vault-relative path, atomically. Returns the
     * relative path on success or {@code null} if the writer is disabled or the
     * write fails. Intended for the Jarvis-loop journal and other audit-driven
     * Obsidian artefacts.
     */
    public String writeMarkdown(Path relativePath, String content) {
        if (!properties.isEnabled()) {
            log.info("obsidian writer disabled — skipping vault write {}", relativePath);
            return null;
        }
        Path target = vaultRoot().resolve(relativePath);
        try {
            atomicWrite(target, content);
            return relativePath.toString();
        } catch (IOException ex) {
            log.error("vault write failed for {}: {}", relativePath, ex.getMessage(), ex);
            return null;
        }
    }

    /** Best-effort removal of a vault file by relative path. */
    public boolean removeIfPresent(String vaultRelativePath) {
        if (vaultRelativePath == null || vaultRelativePath.isBlank()) return false;
        if (!properties.isEnabled()) return false;
        Path absolute = vaultRoot().resolve(vaultRelativePath);
        try {
            return Files.deleteIfExists(absolute);
        } catch (IOException ex) {
            log.warn("vault file remove failed for {}: {}", vaultRelativePath, ex.getMessage());
            return false;
        }
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(),
                target.getFileName().toString() + ".", ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException atomicEx) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path resolveVaultRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            return Paths.get(System.getProperty("user.home"), "JarvisVault");
        }
        if (configured.startsWith("~")) {
            return Paths.get(System.getProperty("user.home"), configured.substring(1)
                    .replaceFirst("^/", ""));
        }
        return Paths.get(configured);
    }

    private String buildFilename(Instant createdAt, String title, String memoryId) {
        Instant ts = createdAt == null ? Instant.now() : createdAt;
        String day = DATE_FMT.format(ts);
        String slug = slugify(title);
        if (slug.isEmpty()) slug = memoryId;
        return day + "-" + slug + ".md";
    }

    static String slugify(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        String slug = normalized
                .replaceAll("[^a-z0-9а-яё]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
            slug = slug.replaceAll("-+$", "");
        }
        return slug;
    }
}
