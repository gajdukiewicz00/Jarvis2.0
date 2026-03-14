package org.jarvis.planner.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Client for routing desktop notifications through api-gateway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesktopNotificationClient {

    private final PcControlActionClient pcControlActionClient;

    public boolean sendNotification(String userId, String title, String message) {
        log.info("Routing desktop notification to user {}: {}", userId, title);
        return pcControlActionClient.sendAction(userId, "NOTIFY", Map.of(
                "title", title,
                "message", message));
    }
}
