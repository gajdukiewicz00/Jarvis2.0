package org.jarvis.agent.voice

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.commands.voice.VoiceFeedback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WakeWordToVoiceBridgeTest {

    @Test
    fun `onWakeWord completes the full session round trip and speaks the reply`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"sessionId":"vs-1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"sessionId":"vs-1","sessionStatus":"COMPLETED","feedback":{"code":"SUCCESS","level":"INFO","spokenText":"Done, sir."}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200)) // endSession

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            val client = VoiceLoopClient(
                voiceGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                userId = "owner",
                feed = feed
            )
            var spoken: VoiceFeedback? = null
            val bridge = WakeWordToVoiceBridge(
                client = client,
                killSwitch = killSwitch,
                feed = feed,
                agentId = "agent-1",
                transcribe = { "turn off my computer" },
                speak = { spoken = it }
            )

            val reply = bridge.onWakeWord()

            assertEquals("Done, sir.", spoken?.spokenText)
            assertEquals("vs-1", reply?.sessionId)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `onWakeWord is suppressed and speaks nothing while the kill switch is engaged`(@TempDir tempDir: Path) {
        val server = MockWebServer()

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            killSwitch.engage("operator", "unsafe")
            val client = VoiceLoopClient(
                voiceGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                userId = "owner",
                feed = feed
            )
            var spoken: VoiceFeedback? = null
            val bridge = WakeWordToVoiceBridge(
                client = client,
                killSwitch = killSwitch,
                feed = feed,
                agentId = "agent-1",
                transcribe = { "should not be called" },
                speak = { spoken = it }
            )

            val reply = bridge.onWakeWord()

            assertNull(reply)
            assertNull(spoken)
            assertEquals(0, server.requestCount)
            assertEquals(AgentEvent.Type.KILL_SWITCH_ENGAGED, feed.recent().last().type)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `onWakeWord closes the session without dispatch when the transcript is blank`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"sessionId":"vs-2"}"""))
        server.enqueue(MockResponse().setResponseCode(200)) // endSession only, no utterance call

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            val client = VoiceLoopClient(
                voiceGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                userId = "owner",
                feed = feed
            )
            var spoken: VoiceFeedback? = null
            val bridge = WakeWordToVoiceBridge(
                client = client,
                killSwitch = killSwitch,
                feed = feed,
                agentId = "agent-1",
                transcribe = { "   " },
                speak = { spoken = it }
            )

            val reply = bridge.onWakeWord()

            assertNull(reply)
            assertNull(spoken)
            assertEquals("/api/v1/voice/sessions", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.endsWith("/end"))
        } finally {
            server.shutdown()
        }
    }
}
