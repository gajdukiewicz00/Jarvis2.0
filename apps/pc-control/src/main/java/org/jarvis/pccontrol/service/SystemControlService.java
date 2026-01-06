package org.jarvis.pccontrol.service;

/**
 * System control service interface for PC operations.
 * Implementations handle volume, media, apps, and notifications.
 */
public interface SystemControlService {

    // Volume control
    void changeVolume(int deltaPercent, String direction) throws Exception;

    void setVolume(int percent) throws Exception;

    void mute() throws Exception;

    void unmute() throws Exception;

    // Media control
    void playPause() throws Exception;

    void pause() throws Exception;

    void next() throws Exception;

    void prev() throws Exception;

    // App control
    void openApp(String appName) throws Exception;

    void executeHotkey(String keyCombination) throws Exception;

    // Notifications
    void sendNotification(String title, String message) throws Exception;

    void beep();
}
