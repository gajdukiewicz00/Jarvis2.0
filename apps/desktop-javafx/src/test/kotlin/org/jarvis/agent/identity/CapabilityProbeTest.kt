package org.jarvis.agent.identity

import org.jarvis.commands.agent.AgentCapability
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.EnumSet

/**
 * [CapabilityProbe] queries real host state (audio mixers, AWT headless flag,
 * PATH executables). This test only asserts the *contract* — a non-null,
 * internally-consistent [EnumSet] that never throws — since the actual
 * capability set is legitimately environment-dependent.
 */
class CapabilityProbeTest {

    @Test
    fun `detect never throws and returns a well-formed capability set`() {
        var capabilities: EnumSet<AgentCapability>? = null
        assertDoesNotThrow { capabilities = CapabilityProbe().detect() }
        assertNotNull(capabilities)
        assertTrue(capabilities is EnumSet<*>)
    }

    @Test
    fun `keyboard and mouse automation are reported together or not at all`() {
        val capabilities = CapabilityProbe().detect()
        val hasKeyboard = capabilities.contains(AgentCapability.KEYBOARD_AUTOMATION)
        val hasMouse = capabilities.contains(AgentCapability.MOUSE_AUTOMATION)
        assertTrue(hasKeyboard == hasMouse, "Robot-backed capabilities are always granted as a pair")
    }

    @Test
    fun `file system capability matches whether the home directory is writable`() {
        val capabilities = CapabilityProbe().detect()
        val homeWritable = java.nio.file.Files.isWritable(java.nio.file.Path.of(System.getProperty("user.home")))
        assertTrue(capabilities.contains(AgentCapability.FILE_SYSTEM) == homeWritable)
    }
}
