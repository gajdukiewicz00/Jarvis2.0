package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.DesktopApplicationsResponse;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.jarvis.pccontrol.model.MouseClickRequest;
import org.jarvis.pccontrol.model.MouseMoveRequest;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenFileRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.model.ScrollRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;

import java.io.IOException;

public interface DesktopControlService {

    DesktopApplicationsResponse listApplications();

    DesktopOperationResponse openApp(OpenAppRequest request) throws IOException;

    DesktopOperationResponse openUrl(OpenUrlRequest request) throws IOException;

    DesktopOperationResponse openFile(OpenFileRequest request) throws IOException;

    DesktopSystemInfo getSystemInfo();

    VolumeState getVolume() throws IOException, InterruptedException;

    VolumeState setVolume(int level) throws IOException, InterruptedException;

    DesktopOperationResponse focusWindow(WindowFocusRequest request) throws IOException, InterruptedException;

    WindowInfo getActiveWindow() throws IOException, InterruptedException;

    WindowListResponse listWindows() throws IOException, InterruptedException;

    DesktopOperationResponse sendKeys(SendKeysRequest request) throws IOException, InterruptedException;

    DesktopOperationResponse mouseClick(MouseClickRequest request) throws IOException, InterruptedException;

    DesktopOperationResponse mouseMove(MouseMoveRequest request) throws IOException, InterruptedException;

    DesktopOperationResponse scroll(ScrollRequest request) throws IOException, InterruptedException;
}
