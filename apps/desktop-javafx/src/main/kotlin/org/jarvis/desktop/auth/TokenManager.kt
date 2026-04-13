package org.jarvis.desktop.auth

import java.util.prefs.Preferences

/**
 * TokenManager - manages JWT access and refresh tokens
 * Stores tokens in user preferences for persistence across app restarts
 */
object TokenManager {
    
    private val prefs = Preferences.userNodeForPackage(TokenManager::class.java)
    
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_ROLE = "user_role"
    private const val KEY_USER_ID = "user_id"
    
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var username: String? = null
    private var userRole: String? = null
    private var userId: String? = null
    
    init {
        // Load tokens from preferences on startup
        loadTokens()
    }
    
    /**
     * Save authentication tokens and user info
     */
    fun saveTokens(access: String, refresh: String, user: String, role: String) {
        accessToken = access
        refreshToken = refresh
        username = user
        userRole = role
        userId = JwtSubjectParser.extractSubject(access)
        
        // Persist to preferences
        prefs.put(KEY_ACCESS_TOKEN, access)
        prefs.put(KEY_REFRESH_TOKEN, refresh)
        prefs.put(KEY_USERNAME, user)
        prefs.put(KEY_USER_ROLE, role)
        if (userId != null) {
            prefs.put(KEY_USER_ID, userId)
        } else {
            prefs.remove(KEY_USER_ID)
        }
        prefs.flush()
    }
    
    /**
     * Get current access token
     */
    fun getAccessToken(): String? = accessToken
    
    /**
     * Get current refresh token
     */
    fun getRefreshToken(): String? = refreshToken
    
    /**
     * Get current username
     */
    fun getUsername(): String? = username
    
    /**
     * Get current user role
     */
    fun getUserRole(): String? = userRole

    /**
     * Get current JWT subject/user ID.
     */
    fun getUserId(): String? = userId
    
    /**
     * Update access token (after refresh)
     */
    fun updateAccessToken(newAccessToken: String) {
        accessToken = newAccessToken
        userId = JwtSubjectParser.extractSubject(newAccessToken) ?: userId
        prefs.put(KEY_ACCESS_TOKEN, newAccessToken)
        if (userId != null) {
            prefs.put(KEY_USER_ID, userId)
        } else {
            prefs.remove(KEY_USER_ID)
        }
        prefs.flush()
    }
    
    /**
     * Clear all tokens (logout)
     */
    fun clearTokens() {
        accessToken = null
        refreshToken = null
        username = null
        userRole = null
        userId = null
        
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs.remove(KEY_REFRESH_TOKEN)
        prefs.remove(KEY_USERNAME)
        prefs.remove(KEY_USER_ROLE)
        prefs.remove(KEY_USER_ID)
        prefs.flush()
    }
    
    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return !accessToken.isNullOrEmpty()
    }
    
    /**
     * Check if user is admin
     */
    fun isAdmin(): Boolean {
        return userRole?.equals("ADMIN", ignoreCase = true) == true
    }
    
    /**
     * Load tokens from preferences
     */
    private fun loadTokens() {
        // 1) Try persisted tokens
        accessToken = prefs.get(KEY_ACCESS_TOKEN, null)
        refreshToken = prefs.get(KEY_REFRESH_TOKEN, null)
        username = prefs.get(KEY_USERNAME, null)
        userRole = prefs.get(KEY_USER_ROLE, null)
        userId = prefs.get(KEY_USER_ID, null)

        if (userId.isNullOrBlank()) {
            userId = JwtSubjectParser.extractSubject(accessToken)
            if (!userId.isNullOrBlank()) {
                prefs.put(KEY_USER_ID, userId)
                prefs.flush()
            }
        }

        // 2) Fallback to environment variables (useful for dev/CI launches)
        if (accessToken.isNullOrBlank()) {
            val envAccess = System.getenv("JARVIS_ACCESS_TOKEN")
            val envRefresh = System.getenv("JARVIS_REFRESH_TOKEN")
            val envUser = System.getenv("JARVIS_USERNAME") ?: System.getenv("JARVIS_USER")
            val envRole = System.getenv("JARVIS_USER_ROLE") ?: "USER"
            val envUserId = System.getenv("JARVIS_USER_ID")

            if (!envAccess.isNullOrBlank()) {
                accessToken = envAccess
                refreshToken = envRefresh
                username = envUser
                userRole = envRole
                userId = envUserId ?: JwtSubjectParser.extractSubject(envAccess)

                // Persist so subsequent restarts keep it
                prefs.put(KEY_ACCESS_TOKEN, accessToken)
                envRefresh?.let { prefs.put(KEY_REFRESH_TOKEN, it) }
                envUser?.let { prefs.put(KEY_USERNAME, it) }
                prefs.put(KEY_USER_ROLE, envRole)
                userId?.let { prefs.put(KEY_USER_ID, it) }
                prefs.flush()
            }
        }
    }
}
