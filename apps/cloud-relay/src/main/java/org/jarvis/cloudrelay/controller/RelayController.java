package org.jarvis.cloudrelay.controller;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.cloudrelay.domain.OpaqueBlob;
import org.jarvis.cloudrelay.domain.OpaqueBlob.Direction;
import org.jarvis.cloudrelay.service.RelayQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Phase 12 — opaque relay endpoints.
 *
 * <p>Uploads accept any bytes and store them as-is. Downloads return
 * stored bytes as base64. The relay never inspects, parses, or
 * decrypts blob content; the only metadata it has is wire-level
 * (routingId, direction, blob age, size).</p>
 *
 * <p>Endpoints are routingId-scoped: a caller who knows a routingId
 * can read/write that queue. RoutingId is itself a 128-bit random UUID
 * generated at pairing time, opaque to the relay, and known only to
 * the paired phone + the on-prem sync-service.</p>
 */
@Slf4j
@RestController
@RequestMapping("/relay/v1")
public class RelayController {

    private final RelayQueueService queues;

    public RelayController(RelayQueueService queues) {
        this.queues = queues;
    }

    /** Phone → home (the sync-service drains this from its side). */
    @PostMapping(value = "/{routingId}/upstream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> uploadFromDevice(@PathVariable String routingId,
                                              @RequestBody byte[] payload) {
        return enqueue(routingId, Direction.TO_HOME, payload);
    }

    /** Home → phone (the phone drains this from its side). */
    @PostMapping(value = "/{routingId}/downstream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> uploadFromHome(@PathVariable String routingId,
                                            @RequestBody byte[] payload) {
        return enqueue(routingId, Direction.TO_DEVICE, payload);
    }

    @GetMapping("/{routingId}/upstream")
    public ResponseEntity<?> drainUpstream(@PathVariable String routingId,
                                           @RequestParam(defaultValue = "10") int limit) {
        return drain(routingId, Direction.TO_HOME, limit);
    }

    @GetMapping("/{routingId}/downstream")
    public ResponseEntity<?> drainDownstream(@PathVariable String routingId,
                                             @RequestParam(defaultValue = "10") int limit) {
        return drain(routingId, Direction.TO_DEVICE, limit);
    }

    @GetMapping("/{routingId}/queue-stats")
    public Map<String, Integer> stats(@PathVariable String routingId) {
        return Map.of(
                "upstreamPending", queues.queueSize(routingId, Direction.TO_HOME),
                "downstreamPending", queues.queueSize(routingId, Direction.TO_DEVICE));
    }

    private ResponseEntity<?> enqueue(String routingId, Direction direction, byte[] payload) {
        try {
            OpaqueBlob blob = queues.enqueue(routingId, direction, payload);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "blobId", blob.blobId(),
                    "size", blob.size(),
                    "queueSize", queues.queueSize(routingId, direction)));
        } catch (RelayQueueService.BlobTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "blob_too_large", "detail", e.getMessage()));
        }
    }

    private ResponseEntity<?> drain(String routingId, Direction direction, int limit) {
        if (limit < 1 || limit > 100) limit = 10;
        List<OpaqueBlob> blobs = queues.drain(routingId, direction, limit);
        var encoder = Base64.getEncoder();
        List<Map<String, Object>> out = blobs.stream().map(b -> Map.<String, Object>of(
                "blobId", b.blobId(),
                "storedAt", b.storedAt().toString(),
                "size", b.size(),
                "payloadB64", encoder.encodeToString(b.payload())
        )).toList();
        return ResponseEntity.ok(Map.of("blobs", out, "remaining",
                queues.queueSize(routingId, direction)));
    }
}
