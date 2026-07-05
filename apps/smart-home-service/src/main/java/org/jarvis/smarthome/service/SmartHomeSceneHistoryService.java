package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeSceneActivation;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory history of scene activations, most-recent first, bounded so it
 * cannot grow without limit across a long-running process.
 */
@Service
public class SmartHomeSceneHistoryService {

    private static final int MAX_HISTORY = 200;

    private final Deque<SmartHomeSceneActivation> history = new ConcurrentLinkedDeque<>();

    /** Record a scene activation and return it, trimming the oldest entries beyond the retention limit. */
    public SmartHomeSceneActivation record(SmartHomeSceneActivation activation) {
        history.addFirst(activation);
        trimToMax();
        return activation;
    }

    /** The {@code limit} most recent activations, most-recent first. */
    public List<SmartHomeSceneActivation> recent(int limit) {
        return history.stream().limit(Math.max(0, limit)).toList();
    }

    public List<SmartHomeSceneActivation> all() {
        return List.copyOf(history);
    }

    private void trimToMax() {
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }
}
