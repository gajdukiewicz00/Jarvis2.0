package org.jarvis.syncservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.repository.SyncRecordStore.IngestOutcome;
import org.jarvis.syncservice.service.RecordSyncService;
import org.jarvis.syncservice.service.RecordSyncService.DeltaPage;
import org.jarvis.syncservice.service.RecordSyncService.InvalidSyncRecordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 12 — generic record-sync REST surface, layered next to the encrypted
 * blob inbox in {@link SyncController}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/sync/records} — ingest one record; dedup + last-write-wins
 *       conflict resolution happen in {@link org.jarvis.syncservice.repository.SyncRecordStore}</li>
 *   <li>{@code GET  /api/v1/sync/records/delta} — bounded page of records changed since a cursor</li>
 *   <li>{@code GET  /api/v1/sync/records/conflicts} — diagnostic view of resolved clashes</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sync/records")
public class RecordSyncController {

    private final RecordSyncService recordSyncService;

    public RecordSyncController(RecordSyncService recordSyncService) {
        this.recordSyncService = recordSyncService;
    }

    @PostMapping
    public ResponseEntity<?> ingest(@RequestBody SyncRecordRequest req) {
        try {
            SyncRecord incoming = req.toDomain();
            IngestOutcome outcome = recordSyncService.ingest(incoming);
            String status = statusOf(outcome);
            HttpStatus code = status.equals("stored") ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(code).body(RecordIngestResponse.from(outcome, status));
        } catch (InvalidSyncRecordException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_record", "reason", e.getMessage()));
        }
    }

    @GetMapping("/delta")
    public DeltaPage delta(
            @RequestParam(name = "since", defaultValue = "0") long since,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return recordSyncService.delta(since, limit);
    }

    @GetMapping("/conflicts")
    public Map<String, Object> conflicts() {
        List<ConflictLogEntry> log = recordSyncService.conflictLog();
        return Map.of("conflicts", log, "count", log.size());
    }

    private static String statusOf(IngestOutcome outcome) {
        if (outcome.wasDuplicate()) return "duplicate";
        if (outcome.hadConflict()) return "conflict_resolved";
        return "stored";
    }

    /** Public DTO for {@code POST /api/v1/sync/records}: the resolved current value for the
     * dedup key, plus which write won if this call resolved an actual clash. */
    public record RecordIngestResponse(String status, SyncRecord record, ConflictLogEntry conflict) {
        static RecordIngestResponse from(IngestOutcome outcome, String status) {
            return new RecordIngestResponse(status, outcome.stored(), outcome.conflict());
        }
    }
}
