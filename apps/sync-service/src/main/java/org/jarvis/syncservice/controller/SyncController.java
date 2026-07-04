package org.jarvis.syncservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.sync.PairingRequest;
import org.jarvis.sync.PairingResponse;
import org.jarvis.sync.SyncEnvelope;
import org.jarvis.syncservice.service.BlobInboxService;
import org.jarvis.syncservice.service.BlobInboxService.InboxResult;
import org.jarvis.syncservice.service.PairingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phase 12 — public REST surface of sync-service.
 *
 * <ul>
 *   <li>{@code POST /api/v1/sync/pairing/init} — start a pairing handshake</li>
 *   <li>{@code POST /api/v1/sync/pairing/complete} — finish pairing, persist device</li>
 *   <li>{@code POST /api/v1/sync/blobs} — accept an encrypted sync blob</li>
 *   <li>{@code GET  /api/v1/sync/health/inbox} — non-secret diagnostic</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final PairingService pairingService;
    private final BlobInboxService inbox;

    public SyncController(PairingService pairingService, BlobInboxService inbox) {
        this.pairingService = pairingService;
        this.inbox = inbox;
    }

    @PostMapping("/pairing/init")
    public PairingService.InitResponse initPairing() {
        return pairingService.initPairing();
    }

    @PostMapping("/pairing/complete")
    public ResponseEntity<?> completePairing(@RequestBody PairingRequest req) {
        try {
            PairingResponse resp = pairingService.completePairing(req);
            return ResponseEntity.ok(resp);
        } catch (PairingService.PairingRejectedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "pairing_rejected", "reason", e.getMessage()));
        }
    }

    @PostMapping("/blobs")
    public ResponseEntity<?> ingestBlob(@RequestBody SyncEnvelope envelope) {
        InboxResult r = inbox.ingest(envelope);
        if (r.ok()) {
            return ResponseEntity.accepted().body(Map.of("status", "accepted"));
        }
        HttpStatus code = switch (r.status()) {
            case UNKNOWN_DEVICE, ROUTING_MISMATCH, TAMPERED -> HttpStatus.UNAUTHORIZED;
            case REPLAY -> HttpStatus.CONFLICT;
            case UNSUPPORTED_KIND -> HttpStatus.UNPROCESSABLE_ENTITY;
            case DISPATCH_FAILED -> HttpStatus.BAD_GATEWAY;
            case ACCEPTED -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(code).body(Map.of(
                "status", r.status().name().toLowerCase(),
                "detail", r.detail() == null ? "" : r.detail()));
    }

    @GetMapping("/health/inbox")
    public Map<String, Object> health() {
        return Map.of("status", "up", "version", SyncEnvelope.CURRENT_VERSION);
    }
}
