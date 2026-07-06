package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WhyRememberedResponseTest {

    @Test
    void fromCarriesRawProvenanceFieldsThrough() {
        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId("mem-1")
                .source("obsidian:03_Memory/Health/note.md")
                .confidence(new BigDecimal("0.80"))
                .scope(MemoryScope.HEALTH.name())
                .privacy("local-only")
                .pinned(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        WhyRememberedResponse response = WhyRememberedResponse.from(note);

        assertThat(response.memoryId()).isEqualTo("mem-1");
        assertThat(response.source()).isEqualTo("obsidian:03_Memory/Health/note.md");
        assertThat(response.confidence()).isEqualByComparingTo("0.80");
        assertThat(response.scope()).isEqualTo("HEALTH");
        assertThat(response.privacy()).isEqualTo("local-only");
        assertThat(response.pinned()).isTrue();
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void explanationMentionsSourceScopeAndConfidenceWhenPresent() {
        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId("mem-1")
                .source("jarvis")
                .confidence(new BigDecimal("0.90"))
                .scope(MemoryScope.USER_PROFILE.name())
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        String explanation = WhyRememberedResponse.from(note).explanation();

        assertThat(explanation).contains("jarvis");
        assertThat(explanation).contains("user profile");
        assertThat(explanation).contains("0.90");
        assertThat(explanation).contains("2026-01-01T00:00:00Z");
    }

    @Test
    void explanationHandlesMissingSourceScopeAndConfidenceGracefully() {
        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId("mem-2")
                .build();

        String explanation = WhyRememberedResponse.from(note).explanation();

        assertThat(explanation).contains("unrecorded source");
        assertThat(explanation).contains("no recorded confidence");
        assertThat(explanation).doesNotContain("scoped as");
    }

    @Test
    void explanationTreatsBlankSourceAsUnrecorded() {
        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId("mem-3")
                .source("   ")
                .build();

        String explanation = WhyRememberedResponse.from(note).explanation();

        assertThat(explanation).contains("unrecorded source");
    }
}
