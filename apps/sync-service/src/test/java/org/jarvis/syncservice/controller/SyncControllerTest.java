package org.jarvis.syncservice.controller;

import org.jarvis.sync.PairingRequest;
import org.jarvis.sync.PairingResponse;
import org.jarvis.syncservice.service.BlobInboxService;
import org.jarvis.syncservice.service.BlobInboxService.InboxResult;
import org.jarvis.syncservice.service.BlobInboxService.Status;
import org.jarvis.syncservice.service.PairingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link SyncController}. The pairing and inbox services
 * are mocked so every branch of the {@code Status -> HttpStatus} mapping in
 * {@link SyncController#ingestBlob} can be driven deterministically without needing
 * real crypto/pairing state (that's covered end-to-end by BlobInboxServiceTest /
 * PairingServiceTest). Security filters are disabled here (covered separately by
 * SyncSecurityConfigTest) so the focus stays on request/response mapping.
 */
@WebMvcTest(controllers = SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PairingService pairingService;

    @MockBean
    private BlobInboxService inbox;

    private static final String ENVELOPE_JSON = """
            {
              "version": 1,
              "routingId": "rt-test",
              "senderDeviceId": "dev-test",
              "nonceB64": "AAAA",
              "ciphertextB64": "BBBB",
              "occurredAtClient": "2026-06-06T10:00:00Z"
            }
            """;

    private static final String PAIRING_REQUEST_JSON = """
            {
              "deviceLabel": "Test Device",
              "identityPubB64": "aWRlbnRpdHk",
              "kexPubB64": "a2V4cHVi",
              "pairingNonceB64": "bm9uY2U",
              "identitySigB64": "c2ln"
            }
            """;

    @Test
    void initPairing_returnsServiceResponse() throws Exception {
        when(pairingService.initPairing())
                .thenReturn(new PairingService.InitResponse("nonce-123", "pub-123"));

        mockMvc.perform(post("/api/v1/sync/pairing/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairingNonceB64").value("nonce-123"))
                .andExpect(jsonPath("$.serverKexPubB64").value("pub-123"));
    }

    @Test
    void completePairing_success_returnsPairingResponse() throws Exception {
        when(pairingService.completePairing(any(PairingRequest.class)))
                .thenReturn(new PairingResponse("srv-kex", "rt-1", "dev-1", Instant.now()));

        mockMvc.perform(post("/api/v1/sync/pairing/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAIRING_REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routingId").value("rt-1"))
                .andExpect(jsonPath("$.senderDeviceId").value("dev-1"));
    }

    @Test
    void completePairing_rejected_returns401WithReason() throws Exception {
        when(pairingService.completePairing(any(PairingRequest.class)))
                .thenThrow(new PairingService.PairingRejectedException("identity_signature_invalid"));

        mockMvc.perform(post("/api/v1/sync/pairing/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAIRING_REQUEST_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("pairing_rejected"))
                .andExpect(jsonPath("$.reason").value("identity_signature_invalid"));
    }

    @Test
    void ingestBlob_accepted_returns202() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.ACCEPTED, null));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void ingestBlob_unknownDevice_returns401() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.UNKNOWN_DEVICE, "device not paired"));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("unknown_device"))
                .andExpect(jsonPath("$.detail").value("device not paired"));
    }

    @Test
    void ingestBlob_routingMismatch_returns401() throws Exception {
        when(inbox.ingest(any()))
                .thenReturn(new InboxResult(Status.ROUTING_MISMATCH, "routingId does not match pairing"));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("routing_mismatch"));
    }

    @Test
    void ingestBlob_tampered_returns401() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.TAMPERED, "AEAD authentication failed"));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("tampered"));
    }

    @Test
    void ingestBlob_replay_returns409() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.REPLAY, "nonce previously seen"));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("replay"));
    }

    @Test
    void ingestBlob_unsupportedKind_returns422() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.UNSUPPORTED_KIND, "unknown_kind"));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("unsupported_kind"));
    }

    @Test
    void ingestBlob_dispatchFailed_returns502WithEmptyDetailWhenNull() throws Exception {
        when(inbox.ingest(any())).thenReturn(new InboxResult(Status.DISPATCH_FAILED, null));

        mockMvc.perform(post("/api/v1/sync/blobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ENVELOPE_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("dispatch_failed"))
                .andExpect(jsonPath("$.detail").value(""));
    }

    @Test
    void health_returnsUpStatusAndVersion() throws Exception {
        mockMvc.perform(get("/api/v1/sync/health/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.version").value(1));
    }
}
