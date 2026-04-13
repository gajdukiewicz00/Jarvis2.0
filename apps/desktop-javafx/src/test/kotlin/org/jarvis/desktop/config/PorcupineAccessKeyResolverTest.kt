package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PorcupineAccessKeyResolverTest {

    @Test
    fun `environment variable wins over files`() {
        val tempDir = Files.createTempDirectory("jarvis-porcupine-env")
        Files.createFile(tempDir.resolve("jarvis-launch.sh"))
        Files.createFile(tempDir.resolve("pom.xml"))
        Files.createDirectories(tempDir.resolve("apps"))
        Files.createDirectories(tempDir.resolve("secrets"))
        Files.writeString(
            tempDir.resolve("secrets").resolve("secrets.env"),
            "PORCUPINE_ACCESS_KEY=file-value\n"
        )

        val resolved = PorcupineAccessKeyResolver.resolve(
            env = mapOf("PORCUPINE_ACCESS_KEY" to "env-value"),
            userDir = tempDir,
            userHome = Files.createTempDirectory("jarvis-home")
        )

        assertEquals("env-value", resolved)
    }

    @Test
    fun `project secrets file is used when env is missing`() {
        val projectRoot = Files.createTempDirectory("jarvis-project")
        Files.createFile(projectRoot.resolve("jarvis-launch.sh"))
        Files.createFile(projectRoot.resolve("pom.xml"))
        Files.createDirectories(projectRoot.resolve("apps"))
        Files.createDirectories(projectRoot.resolve("secrets"))
        Files.writeString(
            projectRoot.resolve("secrets").resolve("secrets.env"),
            "# local secret\nPORCUPINE_ACCESS_KEY='file-value=='\n"
        )

        val nestedDir = Files.createDirectories(projectRoot.resolve("apps").resolve("desktop-javafx"))
        val resolved = PorcupineAccessKeyResolver.resolve(
            env = emptyMap(),
            userDir = nestedDir,
            userHome = Files.createTempDirectory("jarvis-home")
        )

        assertEquals("file-value==", resolved)
    }

    @Test
    fun `home secrets file is fallback when project file is absent`() {
        val userHome = Files.createTempDirectory("jarvis-home")
        Files.createDirectories(userHome.resolve(".jarvis").resolve("secrets"))
        Files.writeString(
            userHome.resolve(".jarvis").resolve("secrets").resolve("secrets.env"),
            "PORCUPINE_ACCESS_KEY=home-value\n"
        )

        val resolved = PorcupineAccessKeyResolver.resolve(
            env = emptyMap(),
            userDir = Files.createTempDirectory("jarvis-random"),
            userHome = userHome
        )

        assertEquals("home-value", resolved)
    }

    @Test
    fun `null is returned when no key is available`() {
        val resolved = PorcupineAccessKeyResolver.resolve(
            env = emptyMap(),
            userDir = Files.createTempDirectory("jarvis-empty"),
            userHome = Files.createTempDirectory("jarvis-home-empty")
        )

        assertNull(resolved)
    }
}
