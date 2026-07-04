package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeSceneServiceTest {

    private SmartHomeSceneService sceneService;

    @BeforeEach
    void setUp() {
        sceneService = new SmartHomeSceneService();
    }

    @Test
    void saveStoresSceneAndAllReturnsIt() {
        SmartHomeScene scene = new SmartHomeScene("movie_night",
                List.of(new SmartHomeScene.SceneStep("kitchen_light", "TURN_OFF", null)));

        SmartHomeScene saved = sceneService.save(scene);

        assertEquals(scene, saved);
        assertEquals(1, sceneService.all().size());
        assertTrue(sceneService.all().contains(scene));
    }

    @Test
    void findReturnsEmptyForUnknownScene() {
        assertTrue(sceneService.find("missing").isEmpty());
    }

    @Test
    void findReturnsStoredSceneByName() {
        SmartHomeScene scene = new SmartHomeScene("good_morning", List.of());
        sceneService.save(scene);

        Optional<SmartHomeScene> found = sceneService.find("good_morning");

        assertTrue(found.isPresent());
        assertEquals(scene, found.get());
    }

    @Test
    void saveReplacesExistingSceneWithSameName() {
        sceneService.save(new SmartHomeScene("evening", List.of()));
        SmartHomeScene replacement = new SmartHomeScene("evening",
                List.of(new SmartHomeScene.SceneStep("desk_lamp", "TOGGLE", null)));

        sceneService.save(replacement);

        assertEquals(1, sceneService.all().size());
        assertEquals(replacement, sceneService.find("evening").get());
    }

    @Test
    void removeDeletesSceneAndReturnsTrue() {
        sceneService.save(new SmartHomeScene("evening", List.of()));

        assertTrue(sceneService.remove("evening"));
        assertTrue(sceneService.find("evening").isEmpty());
    }

    @Test
    void removeReturnsFalseForUnknownScene() {
        assertFalse(sceneService.remove("missing"));
    }
}
