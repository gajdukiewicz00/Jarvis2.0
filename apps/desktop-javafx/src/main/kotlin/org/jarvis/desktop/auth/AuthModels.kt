package org.jarvis.desktop.auth

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val username: String,
    val role: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val role: String = "USER"
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)
