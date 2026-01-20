package org.jarvis.launcher

/**
 * Security utilities for masking sensitive data in logs and diagnostics.
 */
object SecurityUtils {
    /**
     * Mask JWT-like strings (xxx.yyy.zzz format).
     */
    fun maskJwt(text: String): String {
        // Pattern: base64-like strings with dots (JWT format: header.payload.signature)
        val jwtPattern = Regex("""[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}""")
        return jwtPattern.replace(text) { matchResult ->
            val parts = matchResult.value.split(".")
            if (parts.size == 3) {
                "${parts[0].take(4)}...${parts[2].takeLast(4)}"
            } else {
                "***MASKED***"
            }
        }
    }
    
    /**
     * Mask password/secret/token in key=value or JSON format.
     */
    fun maskSecrets(text: String): String {
        var result = text
        
        // Pattern: key=value (case-insensitive for password, secret, token, api_key, etc.)
        val keyValuePattern = Regex("""(?i)(password|secret|token|api[_-]?key|jwt|auth[_-]?token|access[_-]?token|refresh[_-]?token)\s*[=:]\s*([^\s,;}\]]+)""")
        result = keyValuePattern.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues[2]
            "$key=***MASKED***"
        }
        
        // Pattern: JSON format "key": "value"
        val jsonPattern = Regex("""(?i)("(?:password|secret|token|api[_-]?key|jwt|auth[_-]?token|access[_-]?token|refresh[_-]?token)"\s*:\s*")([^"]+)(")""")
        result = jsonPattern.replace(result) { matchResult ->
            "${matchResult.groupValues[1]}***MASKED***${matchResult.groupValues[3]}"
        }
        
        return result
    }
    
    /**
     * Mask environment variable values from jarvis-secrets.
     * Only show that the variable exists, not its value.
     */
    fun maskEnvVarValue(line: String, secretKeys: Set<String>): String {
        var result = line
        
        // Pattern: KEY=value
        secretKeys.forEach { key ->
            val pattern = Regex("""($key\s*=\s*)([^\s]+)""", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { matchResult ->
                "${matchResult.groupValues[1]}***MASKED***"
            }
        }
        
        return result
    }
    
    /**
     * Apply all masking to a line of text.
     */
    fun maskSensitiveData(text: String, secretKeys: Set<String> = emptySet()): String {
        var result = text
        
        // 1. Mask JWT tokens
        result = maskJwt(result)
        
        // 2. Mask password/secret/token patterns
        result = maskSecrets(result)
        
        // 3. Mask environment variable values
        if (secretKeys.isNotEmpty()) {
            result = maskEnvVarValue(result, secretKeys)
        }
        
        return result
    }
    
    /**
     * Get common secret keys that should be masked.
     */
    fun getCommonSecretKeys(): Set<String> {
        return setOf(
            "JWT_SECRET",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_URL",
            "RABBITMQ_PASSWORD",
            "RABBITMQ_USERNAME",
            "POSTGRES_PASSWORD",
            "POSTGRES_USER",
            "API_KEY",
            "SECRET_KEY"
        )
    }
}



