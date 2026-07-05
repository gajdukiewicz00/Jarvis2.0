package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryNoteControllerTest {

    private MemoryNoteService noteService;
    private MemoryForgetService forgetService;
    private MemoryExportService exportService;
    private MemoryNoteController controller;

    @BeforeEach
    void setUp() {
        noteService = mock(MemoryNoteService.class);
        forgetService = mock(MemoryForgetService.class);
        exportService = mock(MemoryExportService.class);
        controller = new MemoryNoteController(noteService, forgetService, exportService);
    }

    private MemoryNoteEntity note(String memoryId, String title) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .title(title)
                .summary("summary")
                .body("body")
                .source("jarvis")
                .privacy("local-only")
                .status("ACTIVE")
                .confidence(new BigDecimal("0.5"))
                .tags(List.of("a"))
                .linkedEntities(List.of())
                .frontmatter(new java.util.HashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void writeDelegatesToServiceAndReturnsOk() {
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("Diploma kickoff").build();
        MemoryNoteEntity created = note("mem-1", "Diploma kickoff");
        when(noteService.write(request)).thenReturn(created);

        ResponseEntity<MemoryNoteEntity> response = controller.write(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void getReturnsNoteWhenPresent() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        when(noteService.get("mem-1")).thenReturn(existing);

        ResponseEntity<MemoryNoteEntity> response = controller.get("mem-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(existing);
    }

    @Test
    void getReturnsNotFoundWhenMissing() {
        when(noteService.get("mem-x")).thenReturn(null);

        ResponseEntity<MemoryNoteEntity> response = controller.get("mem-x");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void listDelegatesCategoryAndLimitToServiceWhenScopeAbsent() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "One"), note("mem-2", "Two"));
        when(noteService.list(MemoryCategory.HEALTH, 10)).thenReturn(notes);

        List<MemoryNoteEntity> result = controller.list(MemoryCategory.HEALTH, null, 10);

        assertThat(result).isEqualTo(notes);
        verify(noteService, times(1)).list(MemoryCategory.HEALTH, 10);
    }

    @Test
    void listDelegatesCategoryAndScopeToServiceWhenScopePresent() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "One"));
        when(noteService.list(MemoryCategory.HEALTH, MemoryScope.HEALTH, 10)).thenReturn(notes);

        List<MemoryNoteEntity> result = controller.list(MemoryCategory.HEALTH, MemoryScope.HEALTH, 10);

        assertThat(result).isEqualTo(notes);
        verify(noteService, times(1)).list(MemoryCategory.HEALTH, MemoryScope.HEALTH, 10);
        verify(noteService, times(0)).list(MemoryCategory.HEALTH, 10);
    }

    @Test
    void pendingDelegatesScopeAndLimitToService() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "Pending"));
        when(noteService.pendingReview(MemoryScope.USER_PROFILE, 20)).thenReturn(notes);

        List<MemoryNoteEntity> result = controller.pending(MemoryScope.USER_PROFILE, 20);

        assertThat(result).isEqualTo(notes);
    }

    @Test
    void updateReturnsUpdatedNoteWhenFound() {
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("Renamed").build();
        MemoryNoteEntity updated = note("mem-1", "Renamed");
        when(noteService.update("mem-1", request)).thenReturn(updated);

        ResponseEntity<MemoryNoteEntity> response = controller.update("mem-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(updated);
    }

    @Test
    void updateReturnsNotFoundWhenMissing() {
        MemoryNoteRequest request = MemoryNoteRequest.builder().title("Renamed").build();
        when(noteService.update("mem-x", request)).thenReturn(null);

        ResponseEntity<MemoryNoteEntity> response = controller.update("mem-x", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void exportDelegatesToServiceExportAllWhenScopeAbsent() {
        List<MemoryNoteEntity> all = List.of(note("mem-1", "One"));
        when(noteService.exportAll(null)).thenReturn(all);

        List<MemoryNoteEntity> result = controller.export(null);

        assertThat(result).isEqualTo(all);
    }

    @Test
    void exportEncryptedDelegatesToExportService() {
        MemoryExportService.ExportEnvelope envelope =
                new MemoryExportService.ExportEnvelope("AES/GCM/NoPadding", "iv", "ciphertext");
        when(exportService.exportEncrypted(MemoryScope.FINANCE)).thenReturn(envelope);

        MemoryExportService.ExportEnvelope result = controller.exportEncrypted(MemoryScope.FINANCE);

        assertThat(result).isEqualTo(envelope);
    }

    @Test
    void importNotesDelegatesToExportService() {
        List<MemoryNoteRequest> requests = List.of(MemoryNoteRequest.builder().title("Imported").build());
        MemoryExportService.ImportSummary summary =
                new MemoryExportService.ImportSummary(1, 1, 0, 0, List.of());
        when(exportService.importNotes(requests)).thenReturn(summary);

        MemoryExportService.ImportSummary result = controller.importNotes(requests);

        assertThat(result).isEqualTo(summary);
    }

    @Test
    void importEncryptedDelegatesToExportService() {
        MemoryExportService.ExportEnvelope envelope =
                new MemoryExportService.ExportEnvelope("AES/GCM/NoPadding", "iv", "ciphertext");
        MemoryExportService.ImportSummary summary =
                new MemoryExportService.ImportSummary(1, 1, 0, 0, List.of());
        when(exportService.importEncrypted(envelope)).thenReturn(summary);

        MemoryExportService.ImportSummary result = controller.importEncrypted(envelope);

        assertThat(result).isEqualTo(summary);
    }

    @Test
    void forgetReturnsOkWhenRemoved() {
        MemoryForgetService.ForgetResult result = new MemoryForgetService.ForgetResult(
                true, "06_System/deleted-memory-log/2026-05-01/mem-1.md", "deleted");
        when(forgetService.forget("mem-1", "owner", "cleanup")).thenReturn(result);

        ResponseEntity<MemoryForgetService.ForgetResult> response =
                controller.forget("mem-1", "owner", "cleanup");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
    }

    @Test
    void forgetReturnsNotFoundWhenNotRemoved() {
        MemoryForgetService.ForgetResult result =
                new MemoryForgetService.ForgetResult(false, null, "not-found");
        when(forgetService.forget("mem-x", null, null)).thenReturn(result);

        ResponseEntity<MemoryForgetService.ForgetResult> response =
                controller.forget("mem-x", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    // ------------------------------------------------ export/import additions

    @Test
    void exportEncryptedOrPlainDelegatesToExportService() {
        MemoryExportService.ExportPayload payload =
                new MemoryExportService.ExportPayload(false, null, List.of(note("mem-1", "One")));
        when(exportService.exportPreferEncrypted(MemoryScope.FINANCE)).thenReturn(payload);

        MemoryExportService.ExportPayload result = controller.exportEncryptedOrPlain(MemoryScope.FINANCE);

        assertThat(result).isEqualTo(payload);
    }

    @Test
    void importWithConflictResolutionParsesModeAndDelegates() {
        List<MemoryNoteRequest> requests = List.of(MemoryNoteRequest.builder().title("Imported").build());
        MemoryExportService.ConflictImportSummary summary =
                new MemoryExportService.ConflictImportSummary(1, 0, 1, 0, 0, List.of());
        when(exportService.importNotesWithConflictResolution(requests, ImportConflictMode.OVERWRITE))
                .thenReturn(summary);

        MemoryExportService.ConflictImportSummary result =
                controller.importWithConflictResolution(requests, "overwrite");

        assertThat(result).isEqualTo(summary);
    }

    @Test
    void importWithConflictResolutionDefaultsModeToSkip() {
        List<MemoryNoteRequest> requests = List.of(MemoryNoteRequest.builder().title("Imported").build());
        MemoryExportService.ConflictImportSummary summary =
                new MemoryExportService.ConflictImportSummary(1, 0, 0, 1, 0, List.of());
        when(exportService.importNotesWithConflictResolution(requests, ImportConflictMode.SKIP))
                .thenReturn(summary);

        MemoryExportService.ConflictImportSummary result =
                controller.importWithConflictResolution(requests, "skip");

        assertThat(result).isEqualTo(summary);
    }

    @Test
    void importEncryptedWithConflictResolutionParsesModeAndDelegates() {
        MemoryExportService.ExportEnvelope envelope =
                new MemoryExportService.ExportEnvelope("AES/GCM/NoPadding", "iv", "ciphertext");
        MemoryExportService.ConflictImportSummary summary =
                new MemoryExportService.ConflictImportSummary(1, 1, 0, 0, 0, List.of());
        when(exportService.importEncryptedWithConflictResolution(envelope, ImportConflictMode.KEEP_BOTH))
                .thenReturn(summary);

        MemoryExportService.ConflictImportSummary result =
                controller.importEncryptedWithConflictResolution(envelope, "keep-both");

        assertThat(result).isEqualTo(summary);
    }

    // ------------------------------------------------------------- why

    @Test
    void whyReturnsExplanationWhenNoteFound() {
        MemoryNoteEntity existing = note("mem-1", "Existing");
        when(noteService.get("mem-1")).thenReturn(existing);

        ResponseEntity<WhyRememberedResponse> response = controller.why("mem-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().memoryId()).isEqualTo("mem-1");
        assertThat(response.getBody().source()).isEqualTo("jarvis");
        assertThat(response.getBody().confidence()).isEqualByComparingTo("0.5");
        assertThat(response.getBody().explanation()).isNotBlank();
    }

    @Test
    void whyReturnsNotFoundWhenMissing() {
        when(noteService.get("mem-x")).thenReturn(null);

        ResponseEntity<WhyRememberedResponse> response = controller.why("mem-x");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}
