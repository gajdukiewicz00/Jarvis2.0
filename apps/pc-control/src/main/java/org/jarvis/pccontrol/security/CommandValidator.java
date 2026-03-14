package org.jarvis.pccontrol.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class CommandValidator {

    @Value("#{'${security.allowed-actions}'.split(',')}")
    private List<String> allowedActions;

    /**
     * Validates if the action is allowed
     * 
     * @throws ResponseStatusException if action is blocked
     */
    public void validateAction(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action type cannot be empty");
        }

        String normalizedAction = normalize(actionType);
        boolean allowed = allowedActions.stream()
                .map(CommandValidator::normalize)
                .anyMatch(normalizedAction::equals);
        if (!allowed) {
            log.warn("Blocked dangerous action attempt: {}", actionType);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Action not allowed: " + actionType + ". Only safe actions are permitted.");
        }

        log.debug("Action validated: {}", actionType);
    }

    private static String normalize(String actionType) {
        return actionType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
