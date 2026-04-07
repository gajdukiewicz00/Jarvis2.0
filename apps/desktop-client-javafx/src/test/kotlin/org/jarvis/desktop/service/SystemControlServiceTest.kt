package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class SystemControlServiceTest {

    private val service = SystemControlService()

    @Test
    fun `executeCheckedCommand throws when process exits non-zero`() {
        val method = SystemControlService::class.java.getDeclaredMethod(
            "executeCheckedCommand",
            Array<String>::class.java
        )
        method.isAccessible = true

        val thrown = assertThrows(InvocationTargetException::class.java) {
            method.invoke(service, arrayOf("bash", "-lc", "echo backend failure >&2; exit 7"))
        }

        val cause = assertInstanceOf(IllegalStateException::class.java, thrown.targetException)
        assertTrue(cause.message!!.contains("failed with code 7"))
        assertTrue(cause.message!!.contains("backend failure"))
    }

    @Test
    fun `executeProcess captures exit code and merged output`() {
        val method = SystemControlService::class.java.getDeclaredMethod(
            "executeProcess",
            Array<String>::class.java
        )
        method.isAccessible = true

        val result = method.invoke(service, arrayOf("bash", "-lc", "printf 'stdout'; printf ' stderr' >&2"))
        val resultClass = result.javaClass
        val exitCodeField = resultClass.getDeclaredField("exitCode").apply { isAccessible = true }
        val outputField = resultClass.getDeclaredField("output").apply { isAccessible = true }

        assertEquals(0, exitCodeField.get(result))
        assertEquals("stdout stderr", outputField.get(result))
    }
}
