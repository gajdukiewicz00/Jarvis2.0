package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObsidianVaultWriterTest {

    @TempDir
    Path tempDir;

    private ObsidianVaultProperties properties;
    private ObsidianMarkdownRenderer renderer;
    private ObsidianVaultWriter writer;

    @BeforeEach
    void setUp() {
        properties = new ObsidianVaultProperties();
        properties.setEnabled(true);
        properties.setVaultPath(tempDir.toString());
        renderer = new ObsidianMarkdownRenderer();
        writer = new ObsidianVaultWriter(properties, renderer);
    }

    private MemoryNoteEntity note(String memoryId, String title) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .title(title)
                .summary("Test summary")
                .body("Test body")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.5"))
                .tags(java.util.List.of("a"))
                .linkedEntities(java.util.List.of())
                .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-05-01T10:00:00Z"))
                .build();
    }

    @Test
    void writesUnderCorrectCategoryDirectory() throws Exception {
        String relative = writer.write(note("mem-1", "Diploma kickoff"));

        assertThat(relative).startsWith("03_Memory/Projects/")
                .endsWith(".md")
                .contains("2026-05-01");
        Path actual = tempDir.resolve(relative);
        assertThat(Files.exists(actual)).isTrue();
        String content = Files.readString(actual);
        assertThat(content).contains("memory_id: \"mem-1\"");
        assertThat(content).contains("# Diploma kickoff");
    }

    @Test
    void writeIsAtomicAndOverridesExisting() throws Exception {
        MemoryNoteEntity n = note("mem-2", "Reminder");
        writer.write(n);
        n.setSummary("Updated summary");
        n.setUpdatedAt(Instant.parse("2026-05-01T11:00:00Z"));
        String secondRelative = writer.write(n);

        Path file = tempDir.resolve(secondRelative);
        String content = Files.readString(file);
        assertThat(content).contains("Updated summary");
        // No leftover *.tmp files in the directory
        try (var dir = Files.list(file.getParent())) {
            assertThat(dir.filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
        }
    }

    @Test
    void disabledWriterIsNoOp() {
        properties.setEnabled(false);
        String relative = writer.write(note("mem-3", "Off"));
        assertThat(relative).isNull();
    }

    @Test
    void slugifyDropsDiacriticsAndPunctuation() {
        assertThat(ObsidianVaultWriter.slugify("Pétya-FINANCE * 2026!"))
                .isEqualTo("petya-finance-2026");
    }

    @Test
    void tombstoneLandsUnderDeletedLogDayDir() throws Exception {
        MemoryNoteEntity n = note("mem-4", "secret");
        writer.write(n);
        String tombRel = writer.writeTombstone(n, "user requested", "owner");
        assertThat(tombRel).startsWith("06_System/deleted-memory-log/");
        Path tomb = tempDir.resolve(tombRel);
        String content = Files.readString(tomb);
        assertThat(content).contains("status: \"deleted\"");
        assertThat(content).doesNotContain("Test body");
    }

    @Test
    void removeIfPresentReturnsTrueOnExistingFile() throws Exception {
        String rel = writer.write(note("mem-5", "doomed"));
        assertThat(writer.removeIfPresent(rel)).isTrue();
        assertThat(Files.exists(tempDir.resolve(rel))).isFalse();
    }
}
