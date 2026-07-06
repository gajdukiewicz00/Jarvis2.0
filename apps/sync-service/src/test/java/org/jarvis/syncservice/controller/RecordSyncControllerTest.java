package org.jarvis.syncservice.controller;

import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.ConflictLogEntry.ConflictReason;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.repository.SyncRecordStore.IngestOutcome;
import org.jarvis.syncservice.service.RecordSyncService;
import org.jarvis.syncservice.service.RecordSyncService.DeltaPage;
import org.jarvis.syncservice.service.RecordSyncService.InvalidSyncRecordException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link RecordSyncController}. RecordSyncService is
 * mocked so the request/response mapping can be driven deterministically; the real
 * dedup/conflict-resolution logic is covered by ConflictResolverTest and
 * InMemorySyncRecordStoreTest.
 */
@WebMvcTest(controllers = RecordSyncController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecordSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecordSyncService recordSyncService;

    private static final String VALID_REQUEST_JSON = """
            {
              "dedupKey": "finance:2026-07-01:txn-1",
              "recordId": "r-1",
              "deviceId": "dev-1",
              "updatedAt": "2026-07-01T10:00:00Z",
              "payload": {"amount": 12.5}
            }
            """;

    private static SyncRecord record(String recordId, long sequence) {
        return new SyncRecord("finance:2026-07-01:txn-1", recordId, "dev-1",
                Instant.parse("2026-07-01T10:00:00Z"), Map.of("amount", 12.5), sequence);
    }

    @Test
    void ingest_newRecord_returns201WithStoredStatus() throws Exception {
        SyncRecord stored = record("r-1", 1L);
        when(recordSyncService.ingest(any())).thenReturn(new IngestOutcome(stored, false, null));

        mockMvc.perform(post("/api/v1/sync/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.record.recordId").value("r-1"))
                .andExpect(jsonPath("$.record.sequence").value(1))
                .andExpect(jsonPath("$.conflict").doesNotExist());
    }

    @Test
    void ingest_duplicate_returns200WithDuplicateStatus() throws Exception {
        SyncRecord stored = record("r-1", 1L);
        when(recordSyncService.ingest(any())).thenReturn(new IngestOutcome(stored, true, null));

        mockMvc.perform(post("/api/v1/sync/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    void ingest_conflictResolved_returns200WithWinnerExposed() throws Exception {
        SyncRecord winner = record("r-1", 2L);
        ConflictLogEntry conflict = new ConflictLogEntry(
                "finance:2026-07-01:txn-1", "r-1", Instant.parse("2026-07-01T10:00:00Z"),
                "r-0", Instant.parse("2026-07-01T09:00:00Z"),
                "r-1", ConflictReason.NEWER_TIMESTAMP, Instant.now());
        when(recordSyncService.ingest(any())).thenReturn(new IngestOutcome(winner, false, conflict));

        mockMvc.perform(post("/api/v1/sync/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("conflict_resolved"))
                .andExpect(jsonPath("$.conflict.winningRecordId").value("r-1"))
                .andExpect(jsonPath("$.conflict.existingRecordId").value("r-0"));
    }

    @Test
    void ingest_invalidRecord_returns400() throws Exception {
        when(recordSyncService.ingest(any())).thenThrow(new InvalidSyncRecordException("dedupKey is required"));

        mockMvc.perform(post("/api/v1/sync/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_record"))
                .andExpect(jsonPath("$.reason").value("dedupKey is required"));
    }

    @Test
    void ingest_missingDedupKeyInBody_returns400WithoutCallingService() throws Exception {
        String badJson = """
                {
                  "recordId": "r-1",
                  "updatedAt": "2026-07-01T10:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/sync/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_record"));
    }

    @Test
    void delta_returnsPageFromService() throws Exception {
        List<SyncRecord> records = List.of(record("r-1", 1L), record("r-2", 2L));
        when(recordSyncService.delta(eq(0L), eq(50)))
                .thenReturn(new DeltaPage(records, 0L, 2L, false));

        mockMvc.perform(get("/api/v1/sync/records/delta").param("since", "0").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void delta_withoutParams_defaultsSinceToZeroAndLimitToNull() throws Exception {
        when(recordSyncService.delta(eq(0L), isNull()))
                .thenReturn(new DeltaPage(List.of(), 0L, 0L, false));

        mockMvc.perform(get("/api/v1/sync/records/delta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(0));
    }

    @Test
    void conflicts_returnsLogFromService() throws Exception {
        ConflictLogEntry conflict = new ConflictLogEntry(
                "k1", "r-new", Instant.now(), "r-old", Instant.now(),
                "r-new", ConflictReason.NEWER_TIMESTAMP, Instant.now());
        when(recordSyncService.conflictLog()).thenReturn(List.of(conflict));

        mockMvc.perform(get("/api/v1/sync/records/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.conflicts[0].winningRecordId").value("r-new"));
    }
}
