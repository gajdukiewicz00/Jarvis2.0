package org.jarvis.security.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidationResponse {
    private boolean valid;
    private String username;
    private Map<String, Object> claims;
    private String error;

    public static TokenValidationResponse success(String username, Map<String, Object> claims) {
        return new TokenValidationResponse(true, username, claims, null);
    }

    public static TokenValidationResponse failure(String error) {
        return new TokenValidationResponse(false, null, null, error);
    }
}
