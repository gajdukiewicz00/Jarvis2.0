package org.jarvis.voicegateway.confirmation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingConfirmationStoreTest {

    private PendingConfirmationStore store;

    @BeforeEach
    void setUp() {
        store = new PendingConfirmationStore();
    }

    @Test
    void createStoresPendingAndTakeReturnsFound() {
        PendingConfirmation created = store.create(
                "user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of("question", "Что на экране?"), "что на экране", "что на экране");

        assertNotNull(created.confirmationId());
        assertEquals("VISION_SCREEN_ANALYZE", created.intent());
        assertEquals("vision", created.service());
        assertEquals(PendingConfirmation.STATUS_PENDING, created.status());
        assertTrue(store.hasPending("user-1"));

        PendingConfirmationStore.TakeResult result = store.take("user-1");

        assertTrue(result.isFound());
        assertEquals("VISION_SCREEN_ANALYZE", result.pending().intent());
        assertEquals("Что на экране?", result.pending().args().get("question"));
        // take() removes: a second take is NONE.
        assertTrue(store.take("user-1").isNone());
        assertFalse(store.hasPending("user-1"));
    }

    @Test
    void takeReturnsNoneWhenNoPending() {
        assertTrue(store.take("nobody").isNone());
    }

    @Test
    void takeReturnsExpiredAfterTtlElapses() throws InterruptedException {
        ReflectionTestUtils.setField(store, "ttlMs", 20L);
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of(), "что на экране", "что на экране");

        Thread.sleep(60L);

        PendingConfirmationStore.TakeResult result = store.take("user-1");
        assertTrue(result.isExpired());
        // Expired entry is removed by take().
        assertFalse(store.hasPending("user-1"));
    }

    @Test
    void peekDoesNotRemoveButHasPendingIsFalseForExpired() throws InterruptedException {
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of(), "что на экране", "что на экране");
        assertTrue(store.peek("user-1").isPresent());
        // peek is non-destructive.
        assertTrue(store.peek("user-1").isPresent());

        ReflectionTestUtils.setField(store, "ttlMs", 20L);
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of(), "что на экране", "что на экране");
        Thread.sleep(60L);
        assertFalse(store.hasPending("user-1"));
        assertFalse(store.peek("user-1").isPresent());
    }

    @Test
    void removeClearsPending() {
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of(), "что на экране", "что на экране");
        store.remove("user-1");
        assertFalse(store.hasPending("user-1"));
    }

    @Test
    void newerConfirmationSupersedesOlderForSameUser() {
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of("question", "old"), "старое", "старое");
        store.create("user-1", "vision", "VISION_SCREEN_ANALYZE", "vision",
                Map.of("question", "new"), "новое", "новое");

        PendingConfirmationStore.TakeResult result = store.take("user-1");
        assertTrue(result.isFound());
        assertEquals("new", result.pending().args().get("question"));
    }
}
