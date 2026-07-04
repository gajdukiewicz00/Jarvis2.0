package org.jarvis.desktop.features.sync

import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Sync / Pairing panel.
 *
 * The gateway sync route is still being added backend-side, so the probe is
 * tolerant: it tries the candidate pairing-status endpoint and maps any failure
 * (404 / connection / not-deployed) into a structured [Result.Unavailable]
 * instead of throwing, so the UI can render an honest degraded state.
 */
class SyncReadModel(
    private val apiClient: ApiClient
) {
    sealed interface Result {
        data class Available(val body: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    fun pairingStatus(): Result {
        return runCatching { apiClient.get("/sync/pairing/status") }
            .fold(
                onSuccess = { Result.Available(it) },
                onFailure = { error ->
                    Result.Unavailable(
                        "Pairing status route is not reachable yet. " +
                            "Sync is being wired into the gateway. " +
                            "(${error.message ?: "unknown error"})"
                    )
                }
            )
    }
}
