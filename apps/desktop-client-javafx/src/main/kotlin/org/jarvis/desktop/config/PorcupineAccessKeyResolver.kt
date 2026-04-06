package org.jarvis.desktop.config

import java.nio.file.Files
import java.nio.file.Path

object PorcupineAccessKeyResolver {
    private const val accessKeyName = "PORCUPINE_ACCESS_KEY"
    private const val explicitFileEnvName = "JARVIS_PORCUPINE_ACCESS_KEY_FILE"
    private const val projectRootEnvName = "JARVIS_PROJECT_ROOT"

    fun resolve(
        env: Map<String, String> = System.getenv(),
        userDir: Path = Path.of(System.getProperty("user.dir")),
        userHome: Path = Path.of(System.getProperty("user.home"))
    ): String? {
        env[accessKeyName]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        candidateFiles(env, userDir, userHome).forEach { candidate ->
            readValue(candidate, accessKeyName)?.let { return it }
        }

        return null
    }

    private fun candidateFiles(env: Map<String, String>, userDir: Path, userHome: Path): List<Path> {
        val candidates = linkedSetOf<Path>()

        env[explicitFileEnvName]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { candidates.add(Path.of(it)) }

        env[projectRootEnvName]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { candidates.add(Path.of(it).resolve("secrets").resolve("secrets.env")) }

        findProjectRoot(userDir)?.let { candidates.add(it.resolve("secrets").resolve("secrets.env")) }
        candidates.add(userHome.resolve(".jarvis").resolve("secrets").resolve("secrets.env"))

        return candidates.toList()
    }

    private fun findProjectRoot(start: Path): Path? {
        var current: Path? = start.toAbsolutePath().normalize()
        while (current != null) {
            if (isProjectRoot(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun isProjectRoot(path: Path): Boolean {
        return Files.exists(path.resolve("jarvis-launch.sh")) &&
            Files.exists(path.resolve("pom.xml")) &&
            Files.isDirectory(path.resolve("apps"))
    }

    private fun readValue(path: Path, key: String): String? {
        if (!Files.isRegularFile(path)) {
            return null
        }

        Files.readAllLines(path).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) {
                return@forEach
            }

            val currentKey = line.substringBefore('=').trim()
            if (currentKey != key) {
                return@forEach
            }

            val value = line.substringAfter('=')
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")

            if (value.isNotEmpty()) {
                return value
            }
        }

        return null
    }
}
