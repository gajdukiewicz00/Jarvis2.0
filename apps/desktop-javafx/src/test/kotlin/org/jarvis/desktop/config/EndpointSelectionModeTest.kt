package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers [EndpointSelectionMode.fromPersisted], which parses the value stored
 * in desktop preferences back into an enum. The persisted-mode branches
 * (AUTO / MANUAL / unparseable) are not exercised by the settings-store
 * round-trip test, which only ever persists the default (unset) mode.
 */
class EndpointSelectionModeTest {

    @Test
    fun `fromPersisted parses the canonical names`() {
        assertEquals(EndpointSelectionMode.AUTO, EndpointSelectionMode.fromPersisted("AUTO"))
        assertEquals(EndpointSelectionMode.MANUAL, EndpointSelectionMode.fromPersisted("MANUAL"))
    }

    @Test
    fun `fromPersisted is case-insensitive and trims surrounding whitespace`() {
        assertEquals(EndpointSelectionMode.AUTO, EndpointSelectionMode.fromPersisted("auto"))
        assertEquals(EndpointSelectionMode.MANUAL, EndpointSelectionMode.fromPersisted("  manual  "))
        assertEquals(EndpointSelectionMode.AUTO, EndpointSelectionMode.fromPersisted("Auto"))
    }

    @Test
    fun `fromPersisted returns null for null blank and unknown values`() {
        assertNull(EndpointSelectionMode.fromPersisted(null))
        assertNull(EndpointSelectionMode.fromPersisted(""))
        assertNull(EndpointSelectionMode.fromPersisted("   "))
        assertNull(EndpointSelectionMode.fromPersisted("hybrid"))
        assertNull(EndpointSelectionMode.fromPersisted("AUTOMATIC"))
    }

    @Test
    fun `enum exposes exactly the two supported modes`() {
        assertEquals(listOf("AUTO", "MANUAL"), EndpointSelectionMode.entries.map { it.name })
    }
}
