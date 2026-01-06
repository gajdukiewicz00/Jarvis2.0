package org.jarvis.desktop.auth

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val username: String,
    val role: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val role: String = "USER"
)

data class RefreshRequest(
    val refreshToken: String
)
