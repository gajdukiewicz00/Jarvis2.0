package org.jarvis.memory.obsidian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.memory.exception.MemoryExportEncryptionUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Roadmap P1 #9 — bulk export/import, including the optional AES-256/GCM
 * encryption layer. {@link MemoryNoteService} is mocked so this stays a pure
 * unit test of {@link MemoryExportService}'s own orchestration.
 */
class MemoryExportServiceTest {

    private MemoryNoteRepository repository;
    private MemoryNoteService noteService;
    private MemoryExportProperties properties;
    private MemoryExportService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        noteService = mock(MemoryNoteService.class);
        properties = new MemoryExportProperties();
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new MemoryExportService(repository, noteService, properties, objectMapper);
    }

    private MemoryNoteEntity note(String memoryId, String title) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .scope(MemoryScope.USER_PROFILE.name())
                .title(title)
                .summary("summary")
                .body("body")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.80"))
                .tags(List.of("a"))
                .linkedEntities(List.of())
                .frontmatter(new LinkedHashMap<>())
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportNotesDelegatesToRepositoryWithScope() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "One"));
        when(repository.searchByCategoryAndScope(eq((String) null), eq(MemoryScope.HEALTH.name()),
                eq("ACTIVE"), any())).thenReturn(notes);

        List<MemoryNoteEntity> result = service.exportNotes(MemoryScope.HEALTH);

        assertThat(result).isEqualTo(notes);
    }

    @Test
    void exportEncryptedThrowsWhenKeyNotConfigured() {
        assertThatThrownBy(() -> service.exportEncrypted(null))
                .isInstanceOf(MemoryExportEncryptionUnavailableException.class);
    }

    @Test
    void importEncryptedThrowsWhenKeyNotConfigured() {
        MemoryExportService.ExportEnvelope envelope =
                new MemoryExportService.ExportEnvelope("AES/GCM/NoPadding", "iv", "ciphertext");

        assertThatThrownBy(() -> service.importEncrypted(envelope))
                .isInstanceOf(MemoryExportEncryptionUnavailableException.class);
    }

    @Test
    void exportEncryptedThenImportEncryptedRoundTripsNoteFields() {
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        when(repository.searchByCategoryAndScope(eq((String) null), eq((String) null), eq("ACTIVE"), any()))
                .thenReturn(List.of(note("mem-1", "Round trip note")));
        when(noteService.writeWithOutcome(any()))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-new", "Round trip note"), false));

        MemoryExportService.ExportEnvelope envelope = service.exportEncrypted(null);
        assertThat(envelope.algorithm()).isEqualTo("AES/GCM/NoPadding");
        assertThat(envelope.ivBase64()).isNotBlank();
        assertThat(envelope.ciphertextBase64()).isNotBlank();

        MemoryExportService.ImportSummary summary = service.importEncrypted(envelope);

        assertThat(summary.received()).isEqualTo(1);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.failed()).isZero();

        ArgumentCaptor<MemoryNoteRequest> captor = ArgumentCaptor.forClass(MemoryNoteRequest.class);
        verify(noteService).writeWithOutcome(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Round trip note");
        assertThat(captor.getValue().getSummary()).isEqualTo("summary");
        assertThat(captor.getValue().getScope()).isEqualTo(MemoryScope.USER_PROFILE);
    }

    @Test
    void importEncryptedFailsWithWrongKey() {
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        when(repository.searchByCategoryAndScope(any(), any(), any(), any()))
                .thenReturn(List.of(note("mem-1", "Secret")));
        MemoryExportService.ExportEnvelope envelope = service.exportEncrypted(null);

        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(wrongKey));

        assertThatThrownBy(() -> service.importEncrypted(envelope))
                .isInstanceOf(MemoryExportEncryptionUnavailableException.class);
    }

    // ---------------------------------------------------------------- import

    @Test
    void importNotesReturnsEmptySummaryForNullOrEmptyList() {
        MemoryExportService.ImportSummary empty = new MemoryExportService.ImportSummary(0, 0, 0, 0, List.of());
        assertThat(service.importNotes(null)).isEqualTo(empty);
        assertThat(service.importNotes(List.of())).isEqualTo(empty);
    }

    @Test
    void importNotesCountsCreatedMergedAndFailed() {
        MemoryNoteRequest created = MemoryNoteRequest.builder().title("Created").build();
        MemoryNoteRequest merged = MemoryNoteRequest.builder().title("Merged").build();
        MemoryNoteRequest failing = MemoryNoteRequest.builder().title("Failing").build();

        when(noteService.writeWithOutcome(created))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-1", "Created"), false));
        when(noteService.writeWithOutcome(merged))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-2", "Merged"), true));
        when(noteService.writeWithOutcome(failing))
                .thenThrow(new IllegalArgumentException("title is required"));

        MemoryExportService.ImportSummary summary = service.importNotes(List.of(created, merged, failing));

        assertThat(summary.received()).isEqualTo(3);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.merged()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("Failing");
    }
}
