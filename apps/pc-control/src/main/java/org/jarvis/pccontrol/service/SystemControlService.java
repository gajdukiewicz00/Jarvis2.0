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

    void openUrl(String url) throws IOException;

    void executeHotkey(String keyCombination) throws IOException, InterruptedException;

    void focusWindow(String title) throws IOException, InterruptedException;

    void closeWindow(String title) throws IOException, InterruptedException;

    void minimizeWindow(String title) throws IOException, InterruptedException;

    void maximizeWindow(String title) throws IOException, InterruptedException;

    void normalizeWindow(String title) throws IOException, InterruptedException;

    void moveMouseAbsolute(int x, int y) throws IOException, InterruptedException;

    void leftClick() throws IOException, InterruptedException;

    void rightClick() throws IOException, InterruptedException;

    void leftButtonDown() throws IOException, InterruptedException;

    void leftButtonUp() throws IOException, InterruptedException;

    void emptyTrash() throws IOException, InterruptedException;

    void openOpticalDrive() throws IOException, InterruptedException;

    void closeOpticalDrive() throws IOException, InterruptedException;

    // Notifications
    void sendNotification(String title, String message) throws IOException, InterruptedException;

    void sleep() throws IOException, InterruptedException;

    void turnMonitorOff() throws IOException, InterruptedException;

    /** Lock the desktop session. Requires a real desktop (no-op in stub mode). */
    void lockScreen() throws IOException, InterruptedException;

    /** Capture the screen to a file. Requires a real desktop (no-op in stub mode). */
    void takeScreenshot(String path) throws IOException, InterruptedException;

    void beep();
}
