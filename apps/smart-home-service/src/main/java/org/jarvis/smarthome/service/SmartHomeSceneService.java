package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeScene;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry of smart-home scenes (in-memory). */
@Service
public class SmartHomeSceneService {

    private final Map<String, SmartHomeScene> scenes = new ConcurrentHashMap<>();

    public SmartHomeScene save(SmartHomeScene scene) {
        scenes.put(scene.name(), scene);
        return scene;
    }

    public List<SmartHomeScene> all() {
        return List.copyOf(scenes.values());
    }

    public Optional<SmartHomeScene> find(String name) {
        return Optional.ofNullable(scenes.get(name));
    }

    public boolean remove(String name) {
        return scenes.remove(name) != null;
    }
}
