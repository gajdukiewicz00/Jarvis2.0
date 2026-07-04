package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObsidianMarkdownRendererTest {

    private final ObsidianMarkdownRenderer renderer = new ObsidianMarkdownRenderer();

    private MemoryNoteEntity sample() {
        return MemoryNoteEntity.builder()
                .memoryId("mem-1")
                .category(MemoryCategory.PROJECTS.name())
                .title("Diploma kickoff")
                .summary("Phase 9 launches Obsidian memory.")
                .body("Stable frontmatter, atomic vault writes,\nforget flow.")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.87"))
                .tags(List.of("jarvis/memory", "phase-9"))
                .linkedEntities(List.of("user:owner", "project:jarvis"))
                .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-05-01T10:00:00Z"))
                .build();
    }

    @Test
    void frontmatterContainsAllRequiredFields() {
        LinkedHashMap<String, String> fm = renderer.frontmatterLines(sample());
        assertThat(fm).containsKeys(
                "type", "memory_id", "source",
                "created_at", "updated_at", "category",
                "tags", "confidence", "linked_entities",
                "privacy", "status");
        assertThat(fm.get("type")).isEqualTo("memory");
        assertThat(fm.get("memory_id")).isEqualTo("\"mem-1\"");
        assertThat(fm.get("status")).isEqualTo("\"active\"");
        assertThat(fm.get("privacy")).isEqualTo("\"local-only\"");
    }

    @Test
    void renderProducesYamlBlockAndMarkdownBody() {
        String md = renderer.render(sample());
        assertThat(md).startsWith("---\n");
        assertThat(md).contains("\nmemory_id: \"mem-1\"\n");
        assertThat(md).contains("\nstatus: \"active\"\n");
        assertThat(md).contains("---\n\n# Diploma kickoff\n");
        assertThat(md).contains("## Summary\n\nPhase 9 launches Obsidian memory.");
        assertThat(md).contains("Stable frontmatter, atomic vault writes,");
        assertThat(md).contains("forget flow.");
    }

    @Test
    void tombstoneCarriesNoBody() {
        String md = renderer.renderTombstone(sample(), "user requested forget", "owner");
        assertThat(md).contains("status: \"deleted\"");
        assertThat(md).contains("deleted_by: \"owner\"");
        assertThat(md).contains("delete_reason: \"user requested forget\"");
        assertThat(md).doesNotContain("Phase 9 launches Obsidian memory.");
        assertThat(md).doesNotContain("forget flow");
        assertThat(md).contains("Memory was forgotten by Jarvis");
    }

    @Test
    void emptyTagsRenderAsEmptyArray() {
        MemoryNoteEntity n = sample().toBuilder()
                .tags(List.of())
                .linkedEntities(List.of())
                .build();
        LinkedHashMap<String, String> fm = renderer.frontmatterLines(n);
        assertThat(fm.get("tags")).isEqualTo("[]");
        assertThat(fm.get("linked_entities")).isEqualTo("[]");
    }

    @Test
    void quotesAndBackslashesInTitleAreEscapedInFrontmatter() {
        MemoryNoteEntity n = sample().toBuilder().memoryId("mem-\"with\"quotes\\backslash").build();
        LinkedHashMap<String, String> fm = renderer.frontmatterLines(n);
        assertThat(fm.get("memory_id")).isEqualTo("\"mem-\\\"with\\\"quotes\\\\backslash\"");
    }
}
