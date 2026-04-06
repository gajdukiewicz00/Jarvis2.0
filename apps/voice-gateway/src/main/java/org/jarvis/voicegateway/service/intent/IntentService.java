package org.jarvis.voicegateway.service.intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final List<IntentHandler> handlers;

    public IntentResult handle(IntentRequest request) {
        IntentResult fallback = null;

        for (IntentHandler handler : handlers) {
            if (handler.canHandle(request)) {
                IntentResult result = handler.handle(request);
                log.debug("Intent handled by {}: action={}, handled={}", handler.getClass().getSimpleName(),
                        result.getAction(), result.isHandled());
                if (result.isHandled()) {
                    return result;
                }
                fallback = result;
            }
        }

        return fallback != null ? fallback : IntentResult.builder()
                .handled(false)
                .action("UNKNOWN")
                .response("I did not understand that command.")
                .build();
    }
}
