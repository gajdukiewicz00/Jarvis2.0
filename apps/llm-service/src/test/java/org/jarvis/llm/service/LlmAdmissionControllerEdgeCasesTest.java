package org.jarvis.llm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmAdmissionControllerEdgeCasesTest {

    @Test
    void interactiveTimesOutWhenPermitHeld() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 8);

        try (LlmAdmissionController.AdmissionTicket held =
                     controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 5)) {
            assertNotNull(held);

            LlmAdmissionController.AdmissionTicket timedOut =
                    controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 0);
            assertNull(timedOut, "should time out while the only permit is held");
            assertEquals(1, controller.getTimeoutCount());
            assertEquals(0, controller.getQueueDepth());
        }
        assertEquals(0, controller.getActiveInferences());
    }

    @Test
    void interactiveRejectedOnQueueOverflow() {
        // maxQueueDepth = 0 => overflow threshold (maxQueueDepth * 2) = 0, so an
        // empty queue already satisfies currentQueue >= threshold for non-voice.
        LlmAdmissionController controller = new LlmAdmissionController(2, 0);

        LlmAdmissionController.AdmissionTicket ticket =
                controller.tryAcquire(LlmAdmissionController.Priority.INTERACTIVE, 5);
        assertNull(ticket, "interactive request must be rejected on queue overflow");
        assertEquals(1, controller.getRejectedCount());
    }

    @Test
    void backgroundRejectedWhenQueueAtCapacityEvenWithFreePermits() {
        // Free permits available, but currentQueue (0) >= maxQueueDepth (0) rejects background.
        LlmAdmissionController controller = new LlmAdmissionController(2, 0);

        LlmAdmissionController.AdmissionTicket ticket =
                controller.tryAcquire(LlmAdmissionController.Priority.BACKGROUND, 5);
        assertNull(ticket, "background rejected when queue is at capacity");
        assertEquals(1, controller.getRejectedCount());
        assertEquals(2, controller.getAvailablePermits());
    }

    @Test
    void ticketCloseIsIdempotent() {
        LlmAdmissionController controller = new LlmAdmissionController(1, 8);

        LlmAdmissionController.AdmissionTicket ticket =
                controller.tryAcquire(LlmAdmissionController.Priority.VOICE, 5);
        assertNotNull(ticket);
        assertEquals(1, controller.getActiveInferences());

        ticket.close();
        ticket.close(); // second close must be a no-op

        assertEquals(0, controller.getActiveInferences());
        assertEquals(1, controller.getAvailablePermits());
    }
}
