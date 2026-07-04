package org.jarvis.agent.confirmation

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationStrategyEnvTest {

    @Test
    fun `default with no env requires human via CLI prompt`() {
        val strategy = ConfirmationStrategy.fromEnv(envValue = null, profile = null, allowAutoApprove = false)
        assertInstanceOf(CliPromptStrategy::class.java, strategy)
    }

    @Test
    fun `prod profile rejects auto-approve even when flag is set`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ConfirmationStrategy.fromEnv(
                envValue = "auto-approve",
                profile = "prod",
                allowAutoApprove = true
            )
        }
        assertTrue(ex.message!!.contains("forbidden in profile='prod'"))
    }

    @Test
    fun `prod profile rejects auto-approve when profile is missing`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ConfirmationStrategy.fromEnv(
                envValue = "auto-approve",
                profile = null,
                allowAutoApprove = true
            )
        }
        assertTrue(ex.message!!.contains("forbidden"))
    }

    @Test
    fun `non-prod profile rejects auto-approve without explicit flag`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ConfirmationStrategy.fromEnv(
                envValue = "auto-approve",
                profile = "test",
                allowAutoApprove = false
            )
        }
        assertTrue(ex.message!!.contains("JARVIS_AGENT_ALLOW_AUTO_APPROVE"))
    }

    @Test
    fun `non-prod profile with explicit flag and env value yields auto-approve`() {
        val strategy = ConfirmationStrategy.fromEnv(
            envValue = "auto-approve",
            profile = "test",
            allowAutoApprove = true
        )
        assertInstanceOf(AutoApproveStrategy::class.java, strategy)
    }

    @Test
    fun `auto-deny env always permitted`() {
        val s1 = ConfirmationStrategy.fromEnv(envValue = "auto-deny", profile = "prod", allowAutoApprove = false)
        val s2 = ConfirmationStrategy.fromEnv(envValue = "deny", profile = null, allowAutoApprove = false)
        assertInstanceOf(AutoDenyStrategy::class.java, s1)
        assertInstanceOf(AutoDenyStrategy::class.java, s2)
    }

    @Test
    fun `cli env yields cli prompt`() {
        val strategy = ConfirmationStrategy.fromEnv(envValue = "cli", profile = "prod", allowAutoApprove = false)
        assertInstanceOf(CliPromptStrategy::class.java, strategy)
    }

    @Test
    fun `unknown env value rejected with clear error`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ConfirmationStrategy.fromEnv(envValue = "yolo", profile = "test", allowAutoApprove = true)
        }
        assertTrue(ex.message!!.contains("Unknown JARVIS_AGENT_CONFIRMATION_STRATEGY"))
    }

    @Test
    fun `AutoApproveStrategy direct construction requires confirmTestUse=true`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            AutoApproveStrategy.forTests("smoke", confirmTestUse = false)
        }
        assertTrue(ex.message!!.contains("confirmTestUse=true"))
    }

    @Test
    fun `AutoApproveStrategy requires non-blank justification`() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoApproveStrategy.forTests("   ", confirmTestUse = true)
        }
    }
}
