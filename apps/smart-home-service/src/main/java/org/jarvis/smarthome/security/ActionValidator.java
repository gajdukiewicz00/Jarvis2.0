package org.jarvis.smarthome.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class ActionValidator {

    @Value("#{'${security.allowed-actions}'.split(',')}")
    private List<String> allowedActions;

    /**
     * Validates if the device action is allowed
     * 
     * @throws ResponseStatusException if action is blocked
     */
    public void validateAction(String action) {
        if (action == null || action.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action cannot be empty");
        }

        String normalizedAction = normalize(action);
        boolean allowed = allowedActions.stream()
                .map(ActionValidator::normalize)
                .anyMatch(normalizedAction::equals);

        if (!allowed) {
            log.warn("Blocked unsafe smart home action: {}", action);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Action not allowed: " + action + ". Only safe device actions are permitted.");
        }

        log.debug("Smart home action validated: {}", action);
    }

    private static String normalize(String action) {
        return action.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
