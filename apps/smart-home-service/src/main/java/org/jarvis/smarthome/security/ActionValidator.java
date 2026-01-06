package org.jarvis.smarthome.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

        if (!allowedActions.contains(action)) {
            log.warn("Blocked unsafe smart home action: {}", action);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Action not allowed: " + action + ". Only safe device actions are permitted.");
        }

        log.debug("Smart home action validated: {}", action);
    }
}
