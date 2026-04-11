package org.jarvis.llm.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmAdmissionControllerTest {

    @Test
    void singleRequestAcquiresAndReleases() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 4);

        try (LlmAdmissionController.AdmissionTicket ticket =
                     controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 5)) {
            assertNotNull(ticket);
            assertEquals(1, controller.getActiveInferences());
            assertEquals(1, controller.getTotalAdmitted());
        }
        assertEquals(0, controller.getActiveInferences());
    }

    @Test
    void backgroundRejectedWhenQueueFull() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 1);

        try (LlmAdmissionController.AdmissionTicket ticket =
                     controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 5)) {
            assertNotNull(ticket);

            LlmAdmissionController.AdmissionTicket bgTicket =
                    controller.tryAcquire(LlmAdmissionController.Priority.BACKGROUND, 1);
            assertNull(bgTicket, "Background should be rejected when semaphore is held and queue >= maxQueueDepth");
            assertEquals(1, controller.getRejectedCount());
        }
    }

    @Test
    void concurrentRequestsRespectLimit() throws Exception {
        LlmAdmissionController controller = new LlmAdmissionController(1, 8);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        int numRequests = 5;

        ExecutorService executor = Executors.newFixedThreadPool(numRequests);
        CountDownLatch latch = new CountDownLatch(numRequests);

        for (int i = 0; i < numRequests; i++) {
            executor.submit(() -> {
                try (LlmAdmissionController.AdmissionTicket ticket =
                             controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 10)) {
                    if (ticket != null) {
                        int cur = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, cur));
                        Thread.sleep(50);
                        currentConcurrent.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(1, maxConcurrent.get(), "Max concurrent should be 1");
    }

    @Test
    void voiceBypassesQueueLimit() {
        LlmAdmissionController controller = new LlmAdmissionController(2, 0);

        LlmAdmissionController.AdmissionTicket voiceTicket =
                controller.tryAcquire(LlmAdmissionController.Priority.VOICE, 5);
        assertNotNull(voiceTicket, "Voice should bypass queue limit");
        voiceTicket.close();
    }

    @Test
    void metricsTrackCorrectly() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 4);

        assertEquals(0, controller.getTotalAdmitted());
        assertEquals(0, controller.getRejectedCount());
        assertEquals(1, controller.getAvailablePermits());

        try (LlmAdmissionController.AdmissionTicket ticket =
                     controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 1)) {
            assertNotNull(ticket);
            assertEquals(1, controller.getTotalAdmitted());
            assertEquals(0, controller.getAvailablePermits());
        }
        assertEquals(1, controller.getAvailablePermits());
    }
}
