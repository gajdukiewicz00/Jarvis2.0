package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Deterministic, read-only coverage for [JarvisPaths].
 *
 * The object bakes its paths from `user.home` at class init and several
 * methods read the real `~/.jarvis` runtime files, so we assert structural
 * relationships and environment-agnostic invariants rather than concrete
 * runtime values. No files are written and no processes are spawned.
 */
class JarvisPathsTest {

    @Test
    fun `directory paths are anchored under jarvis root`() {
        val root = Paths.get(System.getProperty("user.home"), ".jarvis")
        assertEquals(root, JarvisPaths.root)
        assertEquals(root.resolve("logs"), JarvisPaths.logs)
        assertEquals(root.resolve("run"), JarvisPaths.run)
        assertEquals(root.resolve("data"), JarvisPaths.data)
        assertEquals(root.resolve("config"), JarvisPaths.config)
    }

    @Test
    fun `log file paths resolve under logs directory`() {
        assertEquals(JarvisPaths.logs.resolve("launcher.log"), JarvisPaths.launcherLog)
        assertEquals(JarvisPaths.logs.resolve("backend-launch.log"), JarvisPaths.backendLaunchLog)
        assertEquals(JarvisPaths.logs.resolve("desktop.log"), JarvisPaths.desktopLog)
        assertEquals(JarvisPaths.logs.resolve("install.log"), JarvisPaths.installLog)
    }

    @Test
    fun `config and pid paths resolve under their directories`() {
        assertEquals(JarvisPaths.config.resolve("launcher.properties"), JarvisPaths.launcherConfig)
        assertEquals(JarvisPaths.run.resolve("backend.pid"), JarvisPaths.backendPid)
        assertEquals(JarvisPaths.run.resolve("launcher.pid"), JarvisPaths.launcherPid)
        assertEquals(JarvisPaths.run.resolve("desktop.pid"), JarvisPaths.desktopPid)
        assertEquals(JarvisPaths.run.resolve("last-run.json"), JarvisPaths.lastRunSummary)
        assertEquals(JarvisPaths.run.resolve("observability-status.json"), JarvisPaths.observabilityStatus)
    }

    @Test
    fun `release source file is under app directory`() {
        assertEquals(
            JarvisPaths.root.resolve("app").resolve("RELEASE_SOURCE"),
            JarvisPaths.releaseSourceFile
        )
    }

    @Test
    fun `runtime run summary data class exposes all fields`() {
        val summary = JarvisPaths.RuntimeRunSummary(
            timestamp = "2026-01-01T00:00:00Z",
            status = "running",
            apiUrl = "https://api.jarvis.local",
            voiceUrl = "wss://voice.jarvis.local",
            runtimeMode = "k8s",
            grafanaUrl = "https://grafana.jarvis.local"
        )
        assertEquals("running", summary.status)
        assertEquals("k8s", summary.runtimeMode)
        assertEquals("https://api.jarvis.local", summary.apiUrl)

        val copy = summary.copy(status = "stopped")
        assertEquals("stopped", copy.status)
        assertEquals(summary.apiUrl, copy.apiUrl)
        assertTrue(summary != copy)
    }

    @Test
    fun `runtime mode is one of the known modes and matches local flag`() {
        val mode = JarvisPaths.getRuntimeMode()
        assertTrue(mode == "local" || mode == "k8s", "unexpected mode: $mode")
        assertEquals(mode == "local", JarvisPaths.isLocalRuntime())
    }

    @Test
    fun `api gateway url always has an http scheme`() {
        val url = JarvisPaths.getApiGatewayUrl()
        assertTrue(
            url.startsWith("http://") || url.startsWith("https://"),
            "unexpected api url: $url"
        )
    }

    @Test
    fun `grafana url is null for local runtime otherwise http`() {
        val grafana = JarvisPaths.getGrafanaUrl()
        if (JarvisPaths.isLocalRuntime()) {
            assertNull(grafana)
        } else {
            assertNotNull(grafana)
            assertTrue(grafana!!.startsWith("http"), "unexpected grafana url: $grafana")
        }
    }

    @Test
    fun `describe runtime target reports mode and gateway`() {
        val description = JarvisPaths.describeRuntimeTarget()
        assertTrue(description.contains("runtimeMode="), description)
        assertTrue(description.contains("apiGateway="), description)
        assertTrue(description.contains("grafana="), description)
    }

    @Test
    fun `launch and stop scripts resolve to known script names`() {
        val launch = JarvisPaths.getLaunchScript().fileName.toString()
        assertTrue(
            launch == "runtime-up.sh" || launch == "jarvis-launch.sh",
            "unexpected launch script: $launch"
        )
        val stop = JarvisPaths.getStopScript().fileName.toString()
        assertTrue(
            stop == "runtime-down.sh" || stop == "jarvis-stop.sh",
            "unexpected stop script: $stop"
        )
    }

    @Test
    fun `project root resolution returns a non-null path`() {
        assertNotNull(JarvisPaths.getProjectRoot())
    }

    @Test
    fun `loading run summary does not throw and is nullable`() {
        // Exercises the JSON parsing / normalization helpers on whatever
        // last-run.json exists (or returns null when absent).
        JarvisPaths.loadRuntimeRunSummary()
    }
}
