package org.jarvis.cloudrelay.controller;

import org.jarvis.cloudrelay.domain.OpaqueBlob;
import org.jarvis.cloudrelay.domain.OpaqueBlob.Direction;
import org.jarvis.cloudrelay.service.RelayQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link RelayController}. {@link RelayQueueService} is mocked
 * so every branch of the opaque-relay endpoints (upload success, oversized-blob
 * rejection, drain, limit clamping, and queue stats) can be driven deterministically
 * without a real Caffeine-backed queue.
 */
@WebMvcTest(controllers = RelayController.class)
@AutoConfigureMockMvc(addFilters = false)
class RelayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RelayQueueService queues;

    @Test
    void uploadFromDeviceEnqueuesToHomeDirectionAndReturnsAccepted() throws Exception {
        byte[] payload = "phone-payload".getBytes();
        OpaqueBlob blob = new OpaqueBlob(Direction.TO_HOME, payload, Instant.now());
        when(queues.enqueue(eq("rt-1"), eq(Direction.TO_HOME), any(byte[].class))).thenReturn(blob);
        when(queues.queueSize("rt-1", Direction.TO_HOME)).thenReturn(3);

        mockMvc.perform(post("/relay/v1/rt-1/upstream")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.blobId").value(blob.blobId()))
                .andExpect(jsonPath("$.size").value(payload.length))
                .andExpect(jsonPath("$.queueSize").value(3));

        verify(queues).enqueue(eq("rt-1"), eq(Direction.TO_HOME), any(byte[].class));
    }

    @Test
    void uploadFromHomeEnqueuesToDeviceDirectionAndReturnsAccepted() throws Exception {
        byte[] payload = "home-payload".getBytes();
        OpaqueBlob blob = new OpaqueBlob(Direction.TO_DEVICE, payload, Instant.now());
        when(queues.enqueue(eq("rt-1"), eq(Direction.TO_DEVICE), any(byte[].class))).thenReturn(blob);
        when(queues.queueSize("rt-1", Direction.TO_DEVICE)).thenReturn(1);

        mockMvc.perform(post("/relay/v1/rt-1/downstream")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.blobId").value(blob.blobId()))
                .andExpect(jsonPath("$.size").value(payload.length))
                .andExpect(jsonPath("$.queueSize").value(1));

        verify(queues).enqueue(eq("rt-1"), eq(Direction.TO_DEVICE), any(byte[].class));
    }

    @Test
    void uploadRejectedWithPayloadTooLargeWhenServiceThrows() throws Exception {
        when(queues.enqueue(eq("rt-1"), eq(Direction.TO_HOME), any(byte[].class)))
                .thenThrow(new RelayQueueService.BlobTooLargeException(2048, 1024));

        mockMvc.perform(post("/relay/v1/rt-1/upstream")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[2048]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("blob_too_large"))
                .andExpect(jsonPath("$.detail").value("blob size 2048 > limit 1024"));
    }

    @Test
    void drainUpstreamReturnsBlobsAsBase64AndRemainingCount() throws Exception {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        OpaqueBlob blob = new OpaqueBlob(Direction.TO_HOME, payload, Instant.now());
        when(queues.drain(eq("rt-1"), eq(Direction.TO_HOME), eq(5))).thenReturn(List.of(blob));
        when(queues.queueSize("rt-1", Direction.TO_HOME)).thenReturn(2);

        mockMvc.perform(get("/relay/v1/rt-1/upstream").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobs[0].blobId").value(blob.blobId()))
                .andExpect(jsonPath("$.blobs[0].storedAt").value(blob.storedAt().toString()))
                .andExpect(jsonPath("$.blobs[0].size").value(3))
                .andExpect(jsonPath("$.blobs[0].payloadB64")
                        .value(Base64.getEncoder().encodeToString(payload)))
                .andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void drainDownstreamUsesDefaultLimitWhenNotSpecified() throws Exception {
        when(queues.drain(eq("rt-1"), eq(Direction.TO_DEVICE), eq(10))).thenReturn(List.of());
        when(queues.queueSize("rt-1", Direction.TO_DEVICE)).thenReturn(0);

        mockMvc.perform(get("/relay/v1/rt-1/downstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobs").isEmpty())
                .andExpect(jsonPath("$.remaining").value(0));

        verify(queues).drain(eq("rt-1"), eq(Direction.TO_DEVICE), eq(10));
    }

    @Test
    void drainClampsBelowRangeLimitToDefaultOfTen() throws Exception {
        when(queues.drain(eq("rt-1"), eq(Direction.TO_HOME), eq(10))).thenReturn(List.of());
        when(queues.queueSize("rt-1", Direction.TO_HOME)).thenReturn(0);

        mockMvc.perform(get("/relay/v1/rt-1/upstream").param("limit", "0"))
                .andExpect(status().isOk());

        verify(queues).drain(eq("rt-1"), eq(Direction.TO_HOME), eq(10));
    }

    @Test
    void drainClampsAboveRangeLimitToDefaultOfTen() throws Exception {
        when(queues.drain(eq("rt-1"), eq(Direction.TO_HOME), eq(10))).thenReturn(List.of());
        when(queues.queueSize("rt-1", Direction.TO_HOME)).thenReturn(0);

        mockMvc.perform(get("/relay/v1/rt-1/upstream").param("limit", "101"))
                .andExpect(status().isOk());

        verify(queues).drain(eq("rt-1"), eq(Direction.TO_HOME), eq(10));
    }

    @Test
    void queueStatsReportsUpstreamAndDownstreamPendingCounts() throws Exception {
        when(queues.queueSize("rt-1", Direction.TO_HOME)).thenReturn(4);
        when(queues.queueSize("rt-1", Direction.TO_DEVICE)).thenReturn(7);

        mockMvc.perform(get("/relay/v1/rt-1/queue-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstreamPending").value(4))
                .andExpect(jsonPath("$.downstreamPending").value(7));
    }
}
