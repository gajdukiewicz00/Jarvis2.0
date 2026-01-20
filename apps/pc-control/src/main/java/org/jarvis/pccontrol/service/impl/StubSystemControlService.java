package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Stub implementation of SystemControlService for Kubernetes environment.
 * 
 * В K8s нет доступа к X11/PulseAudio, поэтому все операции логируются,
 * но реально не выполняются. Это позволяет сервису стабильно работать
 * в контейнерной среде без падений.
 * 
 * Активируется при pc-control.stub-mode=true.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "pc-control.stub-mode", havingValue = "true")
@Primary // Переопределяет LinuxSystemControlService когда активен профиль k8s
public class StubSystemControlService implements SystemControlService {

    public StubSystemControlService() {
        log.info("🎭 StubSystemControlService initialized - all PC control operations will be simulated");
    }

    @Override
    public void changeVolume(int deltaPercent, String direction) throws Exception {
        log.info("🔊 [STUB] Volume change requested: {}{}%", direction, deltaPercent);
    }

    @Override
    public void setVolume(int percent) throws Exception {
        log.info("🔊 [STUB] Set volume requested: {}%", percent);
    }

    @Override
    public void mute() throws Exception {
        log.info("🔇 [STUB] Mute requested");
    }

    @Override
    public void unmute() throws Exception {
        log.info("🔊 [STUB] Unmute requested");
    }

    @Override
    public void playPause() throws Exception {
        log.info("⏯️ [STUB] Play/Pause requested");
    }

    @Override
    public void pause() throws Exception {
        log.info("⏸️ [STUB] Pause requested");
    }

    @Override
    public void next() throws Exception {
        log.info("⏭️ [STUB] Next track requested");
    }

    @Override
    public void prev() throws Exception {
        log.info("⏮️ [STUB] Previous track requested");
    }

    @Override
    public void beep() {
        log.info("🔔 [STUB] Beep requested");
    }

    @Override
    public void openApp(String appName) throws Exception {
        log.info("🚀 [STUB] Open app requested: {}", appName);
    }

    @Override
    public void executeHotkey(String keyCombination) throws Exception {
        log.info("⌨️ [STUB] Hotkey requested: {}", keyCombination);
    }

    @Override
    public void sendNotification(String title, String message) throws Exception {
        log.info("📢 [STUB] Notification requested - Title: '{}', Message: '{}'", title, message);
    }
}
