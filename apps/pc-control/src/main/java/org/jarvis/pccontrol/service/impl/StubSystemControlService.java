package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;

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
    public void changeVolume(int deltaPercent, String direction) throws IOException, InterruptedException {
        log.info("🔊 [STUB] Volume change requested: {}{}%", direction, deltaPercent);
    }

    @Override
    public void setVolume(int percent) throws IOException, InterruptedException {
        log.info("🔊 [STUB] Set volume requested: {}%", percent);
    }

    @Override
    public void mute() throws IOException, InterruptedException {
        log.info("🔇 [STUB] Mute requested");
    }

    @Override
    public void unmute() throws IOException, InterruptedException {
        log.info("🔊 [STUB] Unmute requested");
    }

    @Override
    public void playPause() throws IOException, InterruptedException {
        log.info("⏯️ [STUB] Play/Pause requested");
    }

    @Override
    public void pause() throws IOException, InterruptedException {
        log.info("⏸️ [STUB] Pause requested");
    }

    @Override
    public void next() throws IOException, InterruptedException {
        log.info("⏭️ [STUB] Next track requested");
    }

    @Override
    public void prev() throws IOException, InterruptedException {
        log.info("⏮️ [STUB] Previous track requested");
    }

    @Override
    public void beep() {
        log.info("🔔 [STUB] Beep requested");
    }

    @Override
    public void openApp(String appName) throws IOException {
        log.info("🚀 [STUB] Open app requested: {}", appName);
    }

    @Override
    public void executeHotkey(String keyCombination) throws IOException, InterruptedException {
        log.info("⌨️ [STUB] Hotkey requested: {}", keyCombination);
    }

    @Override
    public void sendNotification(String title, String message) throws IOException, InterruptedException {
        log.info("📢 [STUB] Notification requested - Title: '{}', Message: '{}'", title, message);
    }
}
