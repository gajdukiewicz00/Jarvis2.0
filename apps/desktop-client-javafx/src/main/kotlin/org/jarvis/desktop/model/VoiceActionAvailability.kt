package org.jarvis.desktop.model

/**
 * Pure derivation of which voice actions are currently permitted.
 *
 * Computed from [VoiceRuntimeState] alone — no side effects, no IO.
 * UI layers use this to enable/disable controls consistently so that
 * invalid actions are impossible to trigger from the interface.
 */
data class VoiceActionAvailability(
    val canPushToTalkStart: Boolean,
    val canPushToTalkRelease: Boolean,
    val canCancelSession: Boolean,
    val canRefreshDevices: Boolean,
    val canSelectInputDevice: Boolean,
    val canSelectOutputDevice: Boolean,
    val canToggleAlwaysListening: Boolean
) {

    companion object {

        fun from(state: VoiceRuntimeState): VoiceActionAvailability {
            val connected = state.connectionPhase.isUsable()
            val busy = state.isBusy

            return VoiceActionAvailability(
                canPushToTalkStart = state.canStartSession && state.hasUsableInput,

                canPushToTalkRelease = state.sessionState == VoiceState.LISTENING
                        && state.pushToTalkActive,

                canCancelSession = state.canCancel,

                canRefreshDevices = !state.isRecording,

                canSelectInputDevice = connected && !busy
                        && state.availableInputDevices.size > 1,

                canSelectOutputDevice = connected && !busy
                        && state.availableOutputDevices.size > 1,

                canToggleAlwaysListening =
                    if (state.alwaysListeningActive) true else connected
            )
        }
    }
}
