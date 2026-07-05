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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    // ------------------------------------------------- export fallback flag

    @Test
    void exportPreferEncryptedReturnsEncryptedPayloadWhenKeyConfigured() {
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        when(repository.searchByCategoryAndScope(any(), any(), eq("ACTIVE"), any()))
                .thenReturn(List.of(note("mem-1", "One")));

        MemoryExportService.ExportPayload payload = service.exportPreferEncrypted(null);

        assertThat(payload.encrypted()).isTrue();
        assertThat(payload.envelope()).isNotNull();
        assertThat(payload.envelope().ciphertextBase64()).isNotBlank();
        assertThat(payload.notes()).isNull();
    }

    @Test
    void exportPreferEncryptedFallsBackToPlaintextWhenKeyNotConfigured() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "One"));
        when(repository.searchByCategoryAndScope(any(), any(), eq("ACTIVE"), any())).thenReturn(notes);

        MemoryExportService.ExportPayload payload = service.exportPreferEncrypted(null);

        assertThat(payload.encrypted()).isFalse();
        assertThat(payload.envelope()).isNull();
        assertThat(payload.notes()).isEqualTo(notes);
    }

    // --------------------------------------- import with conflict resolution

    @Test
    void importNotesWithConflictResolutionReturnsEmptySummaryForNullOrEmptyList() {
        MemoryExportService.ConflictImportSummary empty =
                new MemoryExportService.ConflictImportSummary(0, 0, 0, 0, 0, List.of());
        assertThat(service.importNotesWithConflictResolution(null, ImportConflictMode.SKIP)).isEqualTo(empty);
        assertThat(service.importNotesWithConflictResolution(List.of(), ImportConflictMode.SKIP)).isEqualTo(empty);
    }

    @Test
    void importNotesWithConflictResolutionCreatesWhenNoConflictFound() {
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("Brand new").body("body").build();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(noteService.writeWithOutcome(request))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-new", "Brand new"), false));

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), ImportConflictMode.SKIP);

        assertThat(summary.received()).isEqualTo(1);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.overwritten()).isZero();
        verify(noteService).writeWithOutcome(request);
    }

    @Test
    void importNotesWithConflictResolutionSkipsConflictingMemoryIdWhenModeIsSkip() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .memoryId("mem-1").title("Existing").body("body").build();
        when(noteService.get("mem-1")).thenReturn(existing);

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), ImportConflictMode.SKIP);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.created()).isZero();
        assertThat(summary.overwritten()).isZero();
        verify(noteService, never()).update(any(), any());
        verify(noteService, never()).writeWithOutcome(any(MemoryNoteRequest.class));
        verify(noteService, never()).writeWithOutcome(any(MemoryNoteRequest.class), eq(true));
    }

    @Test
    void importNotesWithConflictResolutionDefaultsToSkipWhenModeIsNull() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .memoryId("mem-1").title("Existing").body("body").build();
        when(noteService.get("mem-1")).thenReturn(existing);

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), null);

        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    void importNotesWithConflictResolutionOverwritesConflictingMemoryIdWhenModeIsOverwrite() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .memoryId("mem-1").title("Updated title").body("body").build();
        when(noteService.get("mem-1")).thenReturn(existing);
        when(noteService.update("mem-1", request)).thenReturn(note("mem-1", "Updated title"));

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), ImportConflictMode.OVERWRITE);

        assertThat(summary.overwritten()).isEqualTo(1);
        assertThat(summary.created()).isZero();
        assertThat(summary.skipped()).isZero();
        verify(noteService).update("mem-1", request);
    }

    @Test
    void importNotesWithConflictResolutionOverwritesNoteMatchedByContentHashWhenNoMemoryIdGiven() {
        MemoryNoteEntity existing = note("mem-existing", "Same title");
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("Same title").body("body").build();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(existing));
        when(noteService.update(eq("mem-existing"), any())).thenReturn(existing);

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), ImportConflictMode.OVERWRITE);

        assertThat(summary.overwritten()).isEqualTo(1);
        verify(noteService).update(eq("mem-existing"), eq(request));
    }

    @Test
    void importNotesWithConflictResolutionKeepsBothCreatingASeparateNote() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        MemoryNoteRequest request = MemoryNoteRequest.builder()
                .memoryId("mem-1").title("Existing").body("body").build();
        when(noteService.get("mem-1")).thenReturn(existing);
        when(noteService.writeWithOutcome(any(MemoryNoteRequest.class), eq(true)))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-new", "Existing"), false));

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(request), ImportConflictMode.KEEP_BOTH);

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.overwritten()).isZero();
        assertThat(summary.skipped()).isZero();
        ArgumentCaptor<MemoryNoteRequest> captor = ArgumentCaptor.forClass(MemoryNoteRequest.class);
        verify(noteService).writeWithOutcome(captor.capture(), eq(true));
        assertThat(captor.getValue().getMemoryId()).isNull();
        assertThat(captor.getValue().getTitle()).isEqualTo("Existing");
    }

    @Test
    void importNotesWithConflictResolutionRecordsFailuresWithoutAbortingBatch() {
        MemoryNoteRequest failing = MemoryNoteRequest.builder().title("Failing").body("body").build();
        MemoryNoteRequest ok = MemoryNoteRequest.builder().title("Ok").body("body2").build();
        when(repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(noteService.writeWithOutcome(failing)).thenThrow(new IllegalArgumentException("boom"));
        when(noteService.writeWithOutcome(ok))
                .thenReturn(new MemoryNoteService.WriteOutcome(note("mem-ok", "Ok"), false));

        MemoryExportService.ConflictImportSummary summary =
                service.importNotesWithConflictResolution(List.of(failing, ok), ImportConflictMode.SKIP);

        assertThat(summary.received()).isEqualTo(2);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.errors().get(0)).contains("Failing");
    }

    @Test
    void importEncryptedWithConflictResolutionDecryptsThenAppliesResolution() {
        properties.setEncryptionKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        MemoryNoteEntity existing = note("mem-1", "Secret");
        when(noteService.get("mem-1")).thenReturn(existing);
        when(repository.searchByCategoryAndScope(any(), any(), eq("ACTIVE"), any()))
                .thenReturn(List.of(note("mem-1", "Secret")));
        MemoryExportService.ExportEnvelope envelope = service.exportEncrypted(null);

        MemoryExportService.ConflictImportSummary summary =
                service.importEncryptedWithConflictResolution(envelope, ImportConflictMode.SKIP);

        assertThat(summary.received()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    void importEncryptedWithConflictResolutionThrowsWhenKeyNotConfigured() {
        MemoryExportService.ExportEnvelope envelope =
                new MemoryExportService.ExportEnvelope("AES/GCM/NoPadding", "iv", "ciphertext");

        assertThatThrownBy(() -> service.importEncryptedWithConflictResolution(envelope, ImportConflictMode.SKIP))
                .isInstanceOf(MemoryExportEncryptionUnavailableException.class);
    }
}
