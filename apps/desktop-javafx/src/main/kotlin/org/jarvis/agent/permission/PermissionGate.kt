package org.jarvis.agent.permission

import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentCapability
import org.slf4j.LoggerFactory

/**
 * Phase 6 — single chokepoint that command and confirmation consumers
 * call before doing privileged work.
 *
 * <p>The gate enforces two policies:</p>
 * <ol>
 *   <li>Kill switch — throws {@link org.jarvis.agent.killswitch.KillSwitchEngagedException}
 *       so the message lands in DLQ rather than silently being ignored.</li>
 *   <li>Capability presence — throws {@link CapabilityMissingException}
 *       when the requested capability is not in the local capability set.</li>
 * </ol>
 *
 * <p>By centralising both checks here, we guarantee SPEC-1's safety
 * invariants regardless of how many executors the agent grows in later
 * phases.</p>
 */
class PermissionGate(
    private val killSwitch: KillSwitchManager,
    private val capabilities: Set<AgentCapability>
) {
    private val log = LoggerFactory.getLogger(PermissionGate::class.java)

    fun require(capability: AgentCapability) {
        killSwitch.ensureClear()
        if (!capabilities.contains(capability)) {
            throw CapabilityMissingException(capability)
        }
    }

    fun requireAny(vararg required: AgentCapability) {
        killSwitch.ensureClear()
        if (required.none { capabilities.contains(it) }) {
            throw CapabilityMissingException(required.first())
        }
    }

    fun ensureClearOnly() {
        killSwitch.ensureClear()
    }

    fun has(capability: AgentCapability): Boolean = capabilities.contains(capability)

    class CapabilityMissingException(missing: AgentCapability) :
        RuntimeException("agent capability '$missing' is not available on this host")
}
