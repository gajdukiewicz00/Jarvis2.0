package org.jarvis.desktop.model

import kotlinx.serialization.Serializable

/**
 * Response from JWT token generation.
 */
@Serializable
data class TokenResponse(
    val token: String,
    val expiresIn: Long
)

/**
 * Request for JWT token generation.
 */
@Serializable
data class TokenRequest(
    val username: String,
    val claims: Map<String, String> = emptyMap()
)
