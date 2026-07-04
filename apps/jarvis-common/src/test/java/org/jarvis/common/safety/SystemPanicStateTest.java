package org.jarvis.common.safety;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPanicStateTest {

    @Test
    void startsNotEngaged() {
        SystemPanicState state = new SystemPanicState();

        assertFalse(state.isEngaged());
        Map<String, Object> snapshot = state.snapshot();
        assertEquals(Boolean.FALSE, snapshot.get("engaged"));
        assertNull(snapshot.get("actor"));
        assertNull(snapshot.get("reason"));
        assertNull(snapshot.get("sinceMillis"));
    }

    @Test
    void engageChangesStateAndReturnsTrueOnFirstEngage() {
        SystemPanicState state = new SystemPanicState();

        boolean changed = state.engage("owner", "emergency stop", 1_000L);

        assertTrue(changed);
        assertTrue(state.isEngaged());
        Map<String, Object> snapshot = state.snapshot();
        assertEquals(Boolean.TRUE, snapshot.get("engaged"));
        assertEquals("owner", snapshot.get("actor"));
        assertEquals("emergency stop", snapshot.get("reason"));
        assertEquals("1000", snapshot.get("sinceMillis"));
    }

    @Test
    void engageAgainWhileAlreadyEngagedReturnsFalseButUpdatesDetails() {
        SystemPanicState state = new SystemPanicState();
        state.engage("owner", "first reason", 1_000L);

        boolean changed = state.engage("owner2", "second reason", 2_000L);

        assertFalse(changed);
        assertTrue(state.isEngaged());
        Map<String, Object> snapshot = state.snapshot();
        assertEquals("owner2", snapshot.get("actor"));
        assertEquals("second reason", snapshot.get("reason"));
        assertEquals("2000", snapshot.get("sinceMillis"));
    }

    @Test
    void engageDefaultsActorAndReasonWhenNull() {
        SystemPanicState state = new SystemPanicState();

        state.engage(null, null, 5_000L);

        Map<String, Object> snapshot = state.snapshot();
        assertEquals("api", snapshot.get("actor"));
        assertEquals("panic engaged", snapshot.get("reason"));
    }

    @Test
    void clearWhenEngagedReturnsTrueAndResetsState() {
        SystemPanicState state = new SystemPanicState();
        state.engage("owner", "reason", 1_000L);

        boolean changed = state.clear("owner", 9_000L);

        assertTrue(changed);
        assertFalse(state.isEngaged());
        Map<String, Object> snapshot = state.snapshot();
        assertEquals("owner", snapshot.get("actor"));
        assertEquals("cleared", snapshot.get("reason"));
        assertEquals("9000", snapshot.get("sinceMillis"));
    }

    @Test
    void clearWhenNotEngagedReturnsFalse() {
        SystemPanicState state = new SystemPanicState();

        boolean changed = state.clear("owner", 9_000L);

        assertFalse(changed);
        assertFalse(state.isEngaged());
    }

    @Test
    void clearDefaultsActorWhenNull() {
        SystemPanicState state = new SystemPanicState();
        state.engage("owner", "reason", 1_000L);

        state.clear(null, 9_000L);

        assertEquals("api", state.snapshot().get("actor"));
    }
}
