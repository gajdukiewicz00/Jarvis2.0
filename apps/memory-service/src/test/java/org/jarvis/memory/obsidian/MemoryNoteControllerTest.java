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
    private MemoryNoteController controller;

    @BeforeEach
    void setUp() {
        noteService = mock(MemoryNoteService.class);
        forgetService = mock(MemoryForgetService.class);
        controller = new MemoryNoteController(noteService, forgetService);
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
    void listDelegatesCategoryAndLimitToService() {
        List<MemoryNoteEntity> notes = List.of(note("mem-1", "One"), note("mem-2", "Two"));
        when(noteService.list(MemoryCategory.HEALTH, 10)).thenReturn(notes);

        List<MemoryNoteEntity> result = controller.list(MemoryCategory.HEALTH, 10);

        assertThat(result).isEqualTo(notes);
        verify(noteService, times(1)).list(MemoryCategory.HEALTH, 10);
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
    void exportDelegatesToServiceExportAll() {
        List<MemoryNoteEntity> all = List.of(note("mem-1", "One"));
        when(noteService.exportAll()).thenReturn(all);

        List<MemoryNoteEntity> result = controller.export();

        assertThat(result).isEqualTo(all);
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
}
