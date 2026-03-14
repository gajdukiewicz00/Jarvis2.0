package org.jarvis.pccontrol.exception;

public class WindowNotFoundException extends DesktopControlException {

    private final String windowIdentifier;

    public WindowNotFoundException(String windowIdentifier) {
        super("Window not found: " + windowIdentifier);
        this.windowIdentifier = windowIdentifier;
    }

    public String getWindowIdentifier() {
        return windowIdentifier;
    }
}
