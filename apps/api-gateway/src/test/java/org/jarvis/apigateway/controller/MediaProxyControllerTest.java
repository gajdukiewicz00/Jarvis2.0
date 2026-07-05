package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaProxyControllerTest {

    @Mock
    private DownstreamProxyService downstreamProxyService;

    @InjectMocks
    private MediaProxyController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "mediaServiceUrl", "http://media-service");
    }

    @Test
    void probeRouteForwardsToMediaService() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/probe");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("probe".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "media-service", "http://media-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("media-service"), eq("http://media-service"));
    }

    @Test
    void jobsRouteForwardsToMediaService() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/media/jobs");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("jobs".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "media-service", "http://media-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("media-service"), eq("http://media-service"));
    }

    @Test
    void pipelineSubPathRouteForwardsToMediaService() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/media/jobs/transcribe");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("transcribe".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "media-service", "http://media-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("media-service"), eq("http://media-service"));
    }

    @Test
    void artifactDownloadSubPathRouteForwardsToMediaService() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/media/jobs/job-1/artifacts/0");
        ResponseEntity<byte[]> expected = ResponseEntity.ok(new byte[] {1, 2, 3});
        when(downstreamProxyService.forward(request, "media-service", "http://media-service")).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(eq(request), eq("media-service"), eq("http://media-service"));
    }
}
