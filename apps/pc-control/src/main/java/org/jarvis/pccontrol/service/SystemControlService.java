package org.jarvis.pccontrol.service;

import java.io.IOException;

/**
 * System control service interface for PC operations.
 * Implementations handle volume, media, apps, and notifications.
 */
public interface SystemControlService {

    // Volume control
    void changeVolume(int deltaPercent, String direction) throws IOException, InterruptedException;

    void setVolume(int percent) throws IOException, InterruptedException;

    void mute() throws IOException, InterruptedException;

    void unmute() throws IOException, InterruptedException;

    // Media control
    void playPause() throws IOException, InterruptedException;

    void pause() throws IOException, InterruptedException;

    void next() throws IOException, InterruptedException;

    void prev() throws IOException, InterruptedException;

    // App control
    void openApp(String appName) throws IOException;

    void executeHotkey(String keyCombination) throws IOException, InterruptedException;

    // Notifications
    void sendNotification(String title, String message) throws IOException, InterruptedException;

    void beep();
}
